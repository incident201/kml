package com.example.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.CryptoManager
import com.example.data.LockRepository
import com.example.data.TransactionState
import com.example.receiver.AlarmReceiver
import com.example.util.SntpClient
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

enum class LockScreenState {
    IDLE, LOCKING, LOCKED, UNLOCKED_PENDING_EXPORT, MISSING_FILE, DELETE_ORIGINAL_PENDING, PERSISTENCE_ERROR
}

enum class OriginalStatus {
    EXISTS, DELETED, UNKNOWN
}

class MainViewModel(
    private val cryptoManager: CryptoManager,
    private val repository: LockRepository,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(LockScreenState.IDLE)
    val uiState: StateFlow<LockScreenState> = _uiState.asStateFlow()

    private val _timeLeftMs = MutableStateFlow(0L)
    val timeLeftMs: StateFlow<Long> = _timeLeftMs.asStateFlow()

    private val _unlockedBitmap = MutableStateFlow<Bitmap?>(null)
    val unlockedBitmap: StateFlow<Bitmap?> = _unlockedBitmap.asStateFlow()

    private val _canCancelLock = MutableStateFlow(false)
    val canCancelLock: StateFlow<Boolean> = _canCancelLock.asStateFlow()

    private val _isStatusUnknown = MutableStateFlow(false)
    val isStatusUnknown: StateFlow<Boolean> = _isStatusUnknown.asStateFlow()

    private val _cleanupFailed = MutableStateFlow(false)
    val cleanupFailed: StateFlow<Boolean> = _cleanupFailed.asStateFlow()

    private val _isUnlockStateSaved = MutableStateFlow(true)
    val isUnlockStateSaved: StateFlow<Boolean> = _isUnlockStateSaved.asStateFlow()

    private var timerJob: kotlinx.coroutines.Job? = null

    init {
        checkCurrentState()
    }

    private fun getMediaStoreUriFromPickerUri(uri: Uri): Uri {
        val uriString = uri.toString()
        if (uriString.startsWith("content://media/external/")) {
            return uri
        }
        var mediaId: Long? = null
        var isVideo = false
        for (segment in uri.pathSegments) {
            if (segment.contains(":") || segment.contains("%3A")) {
                val decoded = Uri.decode(segment)
                val parts = decoded.split(":")
                if (parts.size == 2) {
                    val type = parts[0]
                    val idVal = parts[1].toLongOrNull()
                    if (idVal != null) {
                        mediaId = idVal
                        if (type.lowercase() == "video") {
                            isVideo = true
                        }
                        break
                    }
                }
            }
        }
        if (mediaId == null) {
            val lastSegment = uri.lastPathSegment
            if (lastSegment != null) {
                val idVal = lastSegment.toLongOrNull()
                if (idVal != null) {
                    mediaId = idVal
                } else {
                    val decoded = Uri.decode(lastSegment)
                    if (decoded.contains(":")) {
                        val parts = decoded.split(":")
                        val lastPart = parts.lastOrNull()?.toLongOrNull()
                        if (lastPart != null) {
                            mediaId = lastPart
                            if (parts[0].lowercase() == "video") {
                                isVideo = true
                            }
                        }
                    }
                }
            }
        }
        return if (mediaId != null) {
            if (isVideo) {
                android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId)
            } else {
                android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
            }
        } else {
            uri
        }
    }

    fun checkCurrentState() {
        viewModelScope.launch {
            val state = repository.getTransactionState()
            when (state) {
                TransactionState.IDLE, TransactionState.CLEANED -> {
                    _isStatusUnknown.value = false
                    _cleanupFailed.value = false
                    _uiState.value = LockScreenState.IDLE
                }
                TransactionState.ENCRYPTING, TransactionState.ENCRYPTED_VERIFIED -> {
                    _isStatusUnknown.value = false
                    _cleanupFailed.value = false
                    // Safe cleanup - we didn't delete original yet
                    withContext(Dispatchers.IO) {
                        cryptoManager.deleteEncryptedFile()
                    }
                    repository.clearLockSession()
                    _uiState.value = LockScreenState.IDLE
                }
                TransactionState.DELETE_ORIGINAL_PENDING -> {
                    val originalUriStr = repository.getOriginalUri()
                    if (originalUriStr != null) {
                        val originalUri = Uri.parse(originalUriStr)
                        val status = checkOriginalStatus(originalUri)
                        when (status) {
                            OriginalStatus.DELETED -> {
                                _canCancelLock.value = false
                                _isStatusUnknown.value = false
                                transitionToLockedState()
                            }
                            OriginalStatus.EXISTS -> {
                                _canCancelLock.value = true
                                _isStatusUnknown.value = false
                                _uiState.value = LockScreenState.DELETE_ORIGINAL_PENDING
                            }
                            OriginalStatus.UNKNOWN -> {
                                _canCancelLock.value = false
                                _isStatusUnknown.value = true
                                _uiState.value = LockScreenState.DELETE_ORIGINAL_PENDING
                            }
                        }
                    } else {
                        _isStatusUnknown.value = false
                        repository.clearLockSession()
                        _uiState.value = LockScreenState.IDLE
                    }
                }
                TransactionState.LOCKED -> {
                    _isStatusUnknown.value = false
                    _uiState.value = LockScreenState.LOCKED
                    startTimer()
                }
                TransactionState.UNLOCKED_PENDING_EXPORT -> {
                    _isStatusUnknown.value = false
                    _uiState.value = LockScreenState.UNLOCKED_PENDING_EXPORT
                    loadDecryptedBitmap()
                }
                TransactionState.RESTORED_VERIFIED -> {
                    _isStatusUnknown.value = false
                    completeAndClean()
                }
            }
        }
    }

    private fun transitionToLockedState() {
        val durationMinutes = repository.getDurationMinutes()
        val durationMs = durationMinutes * 60 * 1000L
        
        viewModelScope.launch {
            val currentNtpTime = SntpClient.getCurrentTimeUtc()
            val calculatedEndTime = if (currentNtpTime != null) {
                currentNtpTime + durationMs
            } else {
                System.currentTimeMillis() + durationMs
            }
            val bootTime = SystemClock.elapsedRealtime()
            
            val saveSuccess = repository.saveLockSession(calculatedEndTime, bootTime, durationMs) &&
                              repository.setTransactionState(TransactionState.LOCKED)
            
            if (saveSuccess) {
                if (currentNtpTime != null) {
                    scheduleAlarm(calculatedEndTime, currentNtpTime)
                }
                _uiState.value = LockScreenState.LOCKED
                startTimer()
            } else {
                _uiState.value = LockScreenState.PERSISTENCE_ERROR
            }
        }
    }

    fun lockImage(uri: Uri, durationMinutes: Int, onDeleteOriginal: suspend (Uri) -> Boolean) {
        viewModelScope.launch {
            _uiState.value = LockScreenState.LOCKING
            
            // 1. Extract metadata
            val originalMeta = withContext(Dispatchers.IO) {
                cryptoManager.queryOriginalFileMeta(uri)
            }
            if (originalMeta == null) {
                repository.clearLockSession()
                _uiState.value = LockScreenState.IDLE
                return@launch
            }
            
            // 1b. Get NTP time
            val currentNtpTime = SntpClient.getCurrentTimeUtc()
            if (currentNtpTime == null) {
                repository.clearLockSession()
                _uiState.value = LockScreenState.IDLE
                return@launch
            }

            // Write state, metadata & duration safely
            var writeSuccess = repository.setTransactionState(TransactionState.ENCRYPTING)
            writeSuccess = writeSuccess && repository.saveOriginalMetadata(
                originalUri = uri.toString(),
                displayName = originalMeta.displayName,
                mimeType = originalMeta.mimeType,
                originalSize = originalMeta.size
            )
            writeSuccess = writeSuccess && repository.saveOriginalSha256(originalMeta.sha256)
            writeSuccess = writeSuccess && repository.saveLockDuration(durationMinutes)
            
            if (!writeSuccess) {
                repository.clearLockSession()
                _uiState.value = LockScreenState.IDLE
                return@launch
            }

            // 2. Encrypt & Save using SHA-256 integrity
            val encryptSuccess = withContext(Dispatchers.IO) { 
                cryptoManager.encryptAndSave(uri, originalMeta.sha256) 
            }
            if (!encryptSuccess) {
                withContext(Dispatchers.IO) { cryptoManager.deleteEncryptedFile() }
                repository.clearLockSession()
                _uiState.value = LockScreenState.IDLE
                return@launch
            }

            // 3. Document verified encrypted state
            if (!repository.setTransactionState(TransactionState.ENCRYPTED_VERIFIED)) {
                withContext(Dispatchers.IO) { cryptoManager.deleteEncryptedFile() }
                repository.clearLockSession()
                _uiState.value = LockScreenState.IDLE
                return@launch
            }

            // 4. Deletion flow transition
            if (!repository.setTransactionState(TransactionState.DELETE_ORIGINAL_PENDING)) {
                withContext(Dispatchers.IO) { cryptoManager.deleteEncryptedFile() }
                repository.clearLockSession()
                _uiState.value = LockScreenState.IDLE
                return@launch
            }
            
            val deleted = onDeleteOriginal(uri)
            if (!deleted) {
                // Deletion dialog rejected or failed
                withContext(Dispatchers.IO) { cryptoManager.deleteEncryptedFile() }
                repository.clearLockSession()
                _uiState.value = LockScreenState.IDLE
                return@launch
            }

            // 5. Post-deletion hard verification!
            val status = checkOriginalStatus(uri)
            when (status) {
                OriginalStatus.DELETED -> {
                    _canCancelLock.value = false
                    _isStatusUnknown.value = false
                    // 6. Success locking state setup
                    val durationMs = durationMinutes * 60 * 1000L
                    val endTimeUtc = currentNtpTime + durationMs
                    val bootTime = SystemClock.elapsedRealtime()
                    
                    val saveSuccess = repository.saveLockSession(endTimeUtc, bootTime, durationMs) &&
                                      repository.setTransactionState(TransactionState.LOCKED)
                    
                    if (saveSuccess) {
                        // Schedule Alarm
                        scheduleAlarm(endTimeUtc, currentNtpTime)
                        _uiState.value = LockScreenState.LOCKED
                        startTimer()
                    } else {
                        _uiState.value = LockScreenState.PERSISTENCE_ERROR
                    }
                }
                OriginalStatus.EXISTS -> {
                    _canCancelLock.value = true
                    _isStatusUnknown.value = false
                    _uiState.value = LockScreenState.DELETE_ORIGINAL_PENDING
                }
                OriginalStatus.UNKNOWN -> {
                    _canCancelLock.value = false
                    _isStatusUnknown.value = true
                    _uiState.value = LockScreenState.DELETE_ORIGINAL_PENDING
                }
            }
        }
    }

    fun retryDeleteAndLock(onDeleteOriginal: suspend (Uri) -> Boolean) {
        viewModelScope.launch {
            val originalUriStr = repository.getOriginalUri() ?: return@launch
            val originalUri = Uri.parse(originalUriStr)
            
            _uiState.value = LockScreenState.LOCKING
            // Prompt system deletion. We ignore the boolean result here because the user
            // may accept or dismiss, but we rely entirely on checkOriginalStatus() below
            // to safely and objectively confirm whether the original is deleted.
            onDeleteOriginal(originalUri)
            
            val status = checkOriginalStatus(originalUri)
            when (status) {
                OriginalStatus.DELETED -> {
                    _canCancelLock.value = false
                    _isStatusUnknown.value = false
                    val currentNtpTime = SntpClient.getCurrentTimeUtc()
                    val durationMinutes = repository.getDurationMinutes()
                    val durationMs = durationMinutes * 60 * 1000L
                    val bootTime = SystemClock.elapsedRealtime()
                    
                    val calculatedEndTime = if (currentNtpTime != null) {
                        currentNtpTime + durationMs
                    } else {
                        System.currentTimeMillis() + durationMs
                    }
                    
                    val saveSuccess = repository.saveLockSession(calculatedEndTime, bootTime, durationMs) &&
                                      repository.setTransactionState(TransactionState.LOCKED)
                    
                    if (saveSuccess) {
                        if (currentNtpTime != null) {
                            scheduleAlarm(calculatedEndTime, currentNtpTime)
                        }
                        _uiState.value = LockScreenState.LOCKED
                        startTimer()
                    } else {
                        _uiState.value = LockScreenState.PERSISTENCE_ERROR
                    }
                }
                OriginalStatus.EXISTS -> {
                    _canCancelLock.value = true
                    _isStatusUnknown.value = false
                    _uiState.value = LockScreenState.DELETE_ORIGINAL_PENDING
                }
                OriginalStatus.UNKNOWN -> {
                    _canCancelLock.value = false
                    _isStatusUnknown.value = true
                    _uiState.value = LockScreenState.DELETE_ORIGINAL_PENDING
                }
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (_uiState.value == LockScreenState.LOCKED) {
                try {
                    val endNtpUtx = repository.getEndTimeUtc()
                    val bootAtLock = repository.getBootTimeAtLock()
                    
                    var hasValidTime = false
                    var currentEstimateUtc = 0L

                    if (repository.hadReboot()) {
                        val ntpTime = SntpClient.getCurrentTimeUtc()
                        if (ntpTime != null) {
                            hasValidTime = true
                            currentEstimateUtc = ntpTime
                            val currentBootTime = SystemClock.elapsedRealtime()
                            val passedSinceLock = repository.getDurationMs() - (endNtpUtx - ntpTime)
                            repository.saveLockSession(endNtpUtx, currentBootTime - passedSinceLock, repository.getDurationMs())
                        }
                    } else {
                        val currentBootTime = SystemClock.elapsedRealtime()
                        val passed = currentBootTime - bootAtLock
                        val startNtp = endNtpUtx - repository.getDurationMs()
                        currentEstimateUtc = startNtp + passed
                        hasValidTime = true
                    }

                    if (hasValidTime) {
                        val remaining = endNtpUtx - currentEstimateUtc
                        if (remaining <= 0) {
                            unlock()
                            break
                        } else {
                            _timeLeftMs.value = remaining
                        }
                    }
                } catch (t: Throwable) {
                    t.printStackTrace()
                }

                delay(1000)
            }
        }
    }

    suspend fun verifyAndUnlockWithNtp(): Boolean {
        val ntpTime = SntpClient.getCurrentTimeUtc() ?: return false
        val endNtpUtx = repository.getEndTimeUtc()
        return if (ntpTime >= endNtpUtx) {
            unlock()
            true
        } else {
            false
        }
    }

    private fun unlock() {
        viewModelScope.launch {
            val saved = repository.setTransactionState(TransactionState.UNLOCKED_PENDING_EXPORT)
            _isUnlockStateSaved.value = saved
            _uiState.value = LockScreenState.UNLOCKED_PENDING_EXPORT
            loadDecryptedBitmap()
        }
    }

    fun loadDecryptedBitmap() {
        if (_uiState.value == LockScreenState.UNLOCKED_PENDING_EXPORT && _unlockedBitmap.value == null) {
            viewModelScope.launch {
                val bitmap = withContext(Dispatchers.IO) { cryptoManager.decryptToMemory() }
                if (bitmap != null) {
                    _unlockedBitmap.value = bitmap
                }
            }
        }
    }

    fun cleanupBitmap() {
        val bmp = _unlockedBitmap.value
        bmp?.recycle()
        _unlockedBitmap.value = null
    }

    fun completeAndClean() {
        cleanupBitmap()
        viewModelScope.launch {
            val fileDeleted = withContext(Dispatchers.IO) {
                cryptoManager.deleteEncryptedFile()
            }
            val prefsCleared = repository.clearLockSession()
            if (fileDeleted && prefsCleared) {
                _cleanupFailed.value = false
                _uiState.value = LockScreenState.IDLE
                _isStatusUnknown.value = false
            } else {
                _cleanupFailed.value = true
            }
        }
    }

    suspend fun restoreAndExport(): Boolean {
        return withContext(Dispatchers.IO) {
            val originalSha256 = repository.getOriginalSha256() ?: ""
            val displayName = repository.getOriginalDisplayName() ?: "restored_image_${System.currentTimeMillis()}.jpg"
            val mimeType = repository.getOriginalMimeType() ?: "image/jpeg"
            
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val targetDir = if (mimeType.startsWith("video")) {
                        android.os.Environment.DIRECTORY_MOVIES
                    } else {
                        android.os.Environment.DIRECTORY_PICTURES
                    }
                    put(MediaStore.MediaColumns.RELATIVE_PATH, targetDir)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val contentResolver = context.contentResolver
            val targetUri = if (mimeType.startsWith("video")) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            var insertedUri: Uri? = null
            try {
                insertedUri = contentResolver.insert(targetUri, values)
                if (insertedUri == null) return@withContext false

                val outputStream = contentResolver.openOutputStream(insertedUri)
                if (outputStream == null) {
                    contentResolver.delete(insertedUri, null, null)
                    return@withContext false
                }

                // Streaming copy
                val copySuccess = outputStream.use { out ->
                    cryptoManager.decryptAndStream(out)
                }

                if (!copySuccess) {
                    contentResolver.delete(insertedUri, null, null)
                    return@withContext false
                }

                // Verify saved URI SHA-256 and integrity
                val verified = cryptoManager.verifySavedUriIntegrity(insertedUri, originalSha256)
                if (verified) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val updateValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }
                        contentResolver.update(insertedUri, updateValues, null, null)
                    }
                    repository.setTransactionState(TransactionState.RESTORED_VERIFIED)
                    true
                } else {
                    contentResolver.delete(insertedUri, null, null)
                    false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (insertedUri != null) {
                    try {
                        contentResolver.delete(insertedUri, null, null)
                    } catch (delEx: Exception) {
                        delEx.printStackTrace()
                    }
                }
                false
            }
        }
    }

    private suspend fun checkOriginalStatus(uri: Uri): OriginalStatus {
        return withContext(Dispatchers.IO) {
            val scheme = uri.scheme
            if (scheme == "file") {
                val filePath = uri.path
                if (filePath != null) {
                    val file = java.io.File(filePath)
                    if (file.exists()) {
                        OriginalStatus.EXISTS
                    } else {
                        OriginalStatus.DELETED
                    }
                } else {
                    OriginalStatus.UNKNOWN
                }
            } else {
                val directUri = getMediaStoreUriFromPickerUri(uri)
                try {
                    context.contentResolver.query(
                        directUri,
                        arrayOf(MediaStore.MediaColumns._ID),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.count == 0) {
                            OriginalStatus.DELETED
                        } else {
                            OriginalStatus.EXISTS
                        }
                    } ?: OriginalStatus.UNKNOWN
                } catch (e: Exception) {
                    OriginalStatus.UNKNOWN
                }
            }
        }
    }

    fun cancelPendingLock() {
        viewModelScope.launch {
            if (_uiState.value == LockScreenState.DELETE_ORIGINAL_PENDING && _canCancelLock.value) {
                completeAndClean()
            }
        }
    }

    fun retrySaveLockSessionAndTransition() {
        if (_uiState.value == LockScreenState.PERSISTENCE_ERROR) {
            transitionToLockedState()
        }
    }

    private fun scheduleAlarm(endTimeUtc: Long, currentNtpTime: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAtMillis = System.currentTimeMillis() + (endTimeUtc - currentNtpTime)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }
}

class MainViewModelFactory(
    private val cryptoManager: CryptoManager,
    private val repository: LockRepository,
    private val context: Context
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(cryptoManager, repository, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
