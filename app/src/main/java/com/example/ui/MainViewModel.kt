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
    IDLE, LOCKING, LOCKED, UNLOCKED_PENDING_EXPORT, MISSING_FILE, DELETE_ORIGINAL_PENDING, PERSISTENCE_ERROR, TIME_SYNC_REQUIRED, EMERGENCY_RECOVERY
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

    private val _emergencyTimeLeftMs = MutableStateFlow(0L)
    val emergencyTimeLeftMs: StateFlow<Long> = _emergencyTimeLeftMs.asStateFlow()

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
            // Check plain manifest backup in filesDir first, in case database or keys are corrupt/reinstalled
            val manifest = repository.getRecoveryManifest()
            val hasValidEncryptedFileForManifest = manifest != null && withContext(Dispatchers.IO) {
                cryptoManager.recoverableEncryptedFileIsValid(manifest.sha256)
            }
            if (hasValidEncryptedFileForManifest) {
                val state = try { repository.getTransactionState() } catch (e: Exception) { TransactionState.IDLE }
                // Only fall back to EMERGENCY_RECOVERY if preferences/database state is IDLE or CLEANED,
                // meaning we have no record of an active session in preferences, yet a valid manifest and encrypted file exist and original is deleted.
                if (state == TransactionState.IDLE || state == TransactionState.CLEANED) {
                    val originalUriStr = manifest!!.originalUri
                    val originalStatus = if (originalUriStr.isNotEmpty()) checkOriginalStatus(Uri.parse(originalUriStr)) else OriginalStatus.DELETED
                    if (originalStatus == OriginalStatus.DELETED) {
                        _isStatusUnknown.value = false
                        _uiState.value = LockScreenState.EMERGENCY_RECOVERY
                        startEmergencyTimer()
                        return@launch
                    }
                }
            }

            val state = try { repository.getTransactionState() } catch (e: Exception) { TransactionState.IDLE }
            when (state) {
                TransactionState.IDLE, TransactionState.CLEANED -> {
                    _isStatusUnknown.value = false
                    val hasStaleFile = withContext(Dispatchers.IO) {
                        cryptoManager.encryptedArtifactsExist()
                    }
                    if (hasStaleFile) {
                        _cleanupFailed.value = true
                    } else {
                        _cleanupFailed.value = false
                    }
                    _uiState.value = LockScreenState.IDLE
                }
                TransactionState.ENCRYPTING, TransactionState.ENCRYPTED_VERIFIED -> {
                    _isStatusUnknown.value = false
                    // Safe cleanup - we didn't delete original yet
                    val fileDeleted = withContext(Dispatchers.IO) {
                        cryptoManager.deleteEncryptedFile()
                    }
                    if (!fileDeleted) {
                        _cleanupFailed.value = true
                        _uiState.value = LockScreenState.IDLE
                    } else {
                        repository.deleteRecoveryManifest()
                        val prefsCleared = repository.clearLockSession()
                        if (!prefsCleared) {
                            _cleanupFailed.value = true
                        } else {
                            _cleanupFailed.value = false
                        }
                        _uiState.value = LockScreenState.IDLE
                    }
                }
                TransactionState.DELETE_ORIGINAL_PENDING -> {
                    val originalSha256 = repository.getOriginalSha256() ?: ""
                    val hasRecoverable = withContext(Dispatchers.IO) {
                        cryptoManager.recoverableEncryptedFileIsValid(originalSha256)
                    }
                    if (!hasRecoverable) {
                        val originalUriStr = repository.getOriginalUri()
                        val originalStatus = originalUriStr?.let { checkOriginalStatus(Uri.parse(it)) }

                        if (originalStatus == OriginalStatus.EXISTS) {
                            // Original exists, but encrypted copy is gone or invalid. Safe to abort and clear lock session.
                            performLockboxFailureCleanup()
                        } else {
                            // Original not confirmed exists, but encrypted copy is gone or invalid. Critical loss/missing error.
                            _uiState.value = LockScreenState.MISSING_FILE
                        }
                    } else {
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
                            // We have an encrypted file, but originalUriStr is missing in prefs. Let's delete it.
                            performLockboxFailureCleanup()
                        }
                    }
                }
                TransactionState.LOCKED -> {
                    val originalSha256 = repository.getOriginalSha256() ?: ""
                    val hasRecoverable = withContext(Dispatchers.IO) {
                        cryptoManager.recoverableEncryptedFileIsValid(originalSha256)
                    }
                    if (!hasRecoverable) {
                        _uiState.value = LockScreenState.MISSING_FILE
                    } else {
                        val currentBootTime = SystemClock.elapsedRealtime()
                        val bootTimeAtLock = repository.getBootTimeAtLock()
                        if (currentBootTime < bootTimeAtLock) {
                            repository.markReboot()
                        }
                        _isStatusUnknown.value = false
                        _uiState.value = LockScreenState.LOCKED
                        startTimer()
                    }
                }
                TransactionState.UNLOCKED_PENDING_EXPORT -> {
                    val originalSha256 = repository.getOriginalSha256() ?: ""
                    val hasRecoverable = withContext(Dispatchers.IO) {
                        cryptoManager.recoverableEncryptedFileIsValid(originalSha256)
                    }
                    if (!hasRecoverable) {
                        _uiState.value = LockScreenState.MISSING_FILE
                    } else {
                        _isStatusUnknown.value = false
                        _uiState.value = LockScreenState.UNLOCKED_PENDING_EXPORT
                        loadDecryptedBitmap()
                    }
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
            if (currentNtpTime == null) {
                _uiState.value = LockScreenState.TIME_SYNC_REQUIRED
                return@launch
            }
            var calculatedEndTime = repository.getPlannedEndTimeUtc()
            if (calculatedEndTime == 0L) {
                calculatedEndTime = currentNtpTime + durationMs
            }
            val lockStartUtc = calculatedEndTime - durationMs
            val elapsedSinceStart = (currentNtpTime - lockStartUtc).coerceAtLeast(0L)
            val bootTime = SystemClock.elapsedRealtime() - elapsedSinceStart
            
            val saveSuccess = repository.saveLockSession(calculatedEndTime, bootTime, durationMs) &&
                               repository.setTransactionState(TransactionState.LOCKED)
            
            if (saveSuccess) {
                scheduleAlarm(calculatedEndTime, currentNtpTime)
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
                performLockboxFailureCleanup()
                return@launch
            }
            
            // 1b. Get NTP time
            val currentNtpTime = SntpClient.getCurrentTimeUtc()
            if (currentNtpTime == null) {
                performLockboxFailureCleanup()
                return@launch
            }
            val startBootTime = SystemClock.elapsedRealtime()
            val durationMs = durationMinutes * 60 * 1000L
            val plannedEndTimeUtc = currentNtpTime + durationMs

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
            writeSuccess = writeSuccess && repository.savePlannedEndTimeUtc(plannedEndTimeUtc)
            writeSuccess = writeSuccess && repository.saveRecoveryManifest(
                originalUri = uri.toString(),
                displayName = originalMeta.displayName ?: "",
                mimeType = originalMeta.mimeType ?: "image/jpeg",
                originalSize = originalMeta.size,
                sha256 = originalMeta.sha256,
                endTimeUtc = plannedEndTimeUtc,
                durationMs = durationMs
            )
            
            if (!writeSuccess) {
                performLockboxFailureCleanup()
                return@launch
            }

            // 2. Encrypt & Save using SHA-256 integrity
            val encryptSuccess = withContext(Dispatchers.IO) { 
                cryptoManager.encryptAndSave(uri, originalMeta.sha256) 
            }
            if (!encryptSuccess) {
                performLockboxFailureCleanup()
                return@launch
            }

            // 3. Document verified encrypted state
            if (!repository.setTransactionState(TransactionState.ENCRYPTED_VERIFIED)) {
                performLockboxFailureCleanup()
                return@launch
            }

            // 4. Deletion flow transition
            if (!repository.setTransactionState(TransactionState.DELETE_ORIGINAL_PENDING)) {
                performLockboxFailureCleanup()
                return@launch
            }
            
            val deleted = onDeleteOriginal(uri)
            if (!deleted) {
                // Deletion dialog rejected or failed
                performLockboxFailureCleanup()
                return@launch
            }

            // 5. Post-deletion hard verification!
            val status = checkOriginalStatus(uri)
            when (status) {
                OriginalStatus.DELETED -> {
                    _canCancelLock.value = false
                    _isStatusUnknown.value = false
                    // 6. Success locking state setup
                    var endTimeUtc = repository.getPlannedEndTimeUtc()
                    if (endTimeUtc == 0L) {
                        endTimeUtc = currentNtpTime + durationMs
                    }
                    val bootTime = startBootTime
                    
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
            
            val originalSha256 = repository.getOriginalSha256() ?: ""
            val hasRecoverable = withContext(Dispatchers.IO) {
                cryptoManager.recoverableEncryptedFileIsValid(originalSha256)
            }
            if (!hasRecoverable) {
                val status = checkOriginalStatus(originalUri)
                if (status == OriginalStatus.EXISTS) {
                    performLockboxFailureCleanup()
                } else {
                    _uiState.value = LockScreenState.MISSING_FILE
                }
                return@launch
            }

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
                    if (currentNtpTime == null) {
                        _uiState.value = LockScreenState.TIME_SYNC_REQUIRED
                        return@launch
                    }
                    val durationMinutes = repository.getDurationMinutes()
                    val durationMs = durationMinutes * 60 * 1000L
                    var calculatedEndTime = repository.getPlannedEndTimeUtc()
                    if (calculatedEndTime == 0L) {
                        calculatedEndTime = currentNtpTime + durationMs
                    }
                    val lockStartUtc = calculatedEndTime - durationMs
                    val elapsedSinceStart = (currentNtpTime - lockStartUtc).coerceAtLeast(0L)
                    val bootTime = SystemClock.elapsedRealtime() - elapsedSinceStart
                    
                    val saveSuccess = repository.saveLockSession(calculatedEndTime, bootTime, durationMs) &&
                                      repository.setTransactionState(TransactionState.LOCKED)
                    
                    if (saveSuccess) {
                        scheduleAlarm(calculatedEndTime, currentNtpTime)
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
            val currentBootTimeInitial = SystemClock.elapsedRealtime()
            val bootTimeAtLockInitial = repository.getBootTimeAtLock()
            if (currentBootTimeInitial < bootTimeAtLockInitial) {
                repository.markReboot()
            }
            if (repository.hadReboot()) {
                val ntpTime = SntpClient.getCurrentTimeUtc()
                if (ntpTime == null) {
                    _uiState.value = LockScreenState.TIME_SYNC_REQUIRED
                    return@launch
                } else {
                    val endNtpUtx = repository.getEndTimeUtc()
                    val currentBootTime = SystemClock.elapsedRealtime()
                    val passedSinceLock = repository.getDurationMs() - (endNtpUtx - ntpTime)
                    repository.saveLockSession(endNtpUtx, currentBootTime - passedSinceLock, repository.getDurationMs())
                }
            }

            while (_uiState.value == LockScreenState.LOCKED) {
                try {
                    val endNtpUtx = repository.getEndTimeUtc()
                    val bootAtLock = repository.getBootTimeAtLock()
                    
                    val currentBootTime = SystemClock.elapsedRealtime()
                    val passed = currentBootTime - bootAtLock
                    val startNtp = endNtpUtx - repository.getDurationMs()
                    val currentEstimateUtc = startNtp + passed

                    val remaining = endNtpUtx - currentEstimateUtc
                    if (remaining <= 0) {
                        unlock()
                        break
                    } else {
                        _timeLeftMs.value = remaining
                    }
                } catch (t: Throwable) {
                    t.printStackTrace()
                }

                delay(1000)
            }
        }
    }

    fun retryTimeSync() {
        checkCurrentState()
    }

    private var emergencyTimerJob: kotlinx.coroutines.Job? = null

    fun switchToEmergencyRecovery() {
        if (_uiState.value == LockScreenState.PERSISTENCE_ERROR) {
            val manifest = repository.getRecoveryManifest()
            if (manifest != null) {
                _uiState.value = LockScreenState.EMERGENCY_RECOVERY
                startEmergencyTimer()
            }
        }
    }

    private fun startEmergencyTimer() {
        emergencyTimerJob?.cancel()
        emergencyTimerJob = viewModelScope.launch {
            while (_uiState.value == LockScreenState.EMERGENCY_RECOVERY) {
                val manifest = repository.getRecoveryManifest()
                if (manifest == null) {
                    _uiState.value = LockScreenState.IDLE
                    break
                }
                val ntpTime = SntpClient.getCurrentTimeUtc()
                if (ntpTime == null) {
                    _emergencyTimeLeftMs.value = -1L // indicates sync/offline status in UI
                } else {
                    val remaining = manifest.endTimeUtc - ntpTime
                    if (remaining <= 0) {
                        _emergencyTimeLeftMs.value = 0L
                    } else {
                        _emergencyTimeLeftMs.value = remaining
                    }
                }
                delay(1000)
            }
        }
    }

    suspend fun emergencyExport(): Boolean {
        return withContext(Dispatchers.IO) {
            val manifest = repository.getRecoveryManifest() ?: return@withContext false
            val originalSha256 = manifest.sha256
            val displayName = manifest.displayName.ifEmpty { "restored_image_${System.currentTimeMillis()}.jpg" }
            val mimeType = manifest.mimeType.ifEmpty { "image/jpeg" }
            
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
                        val rowsUpdated = contentResolver.update(insertedUri, updateValues, null, null)
                        if (rowsUpdated <= 0) {
                            contentResolver.delete(insertedUri, null, null)
                            return@withContext false
                        }

                        // Query verification for IS_PENDING
                        var pendingStatusVerified = false
                        try {
                            contentResolver.query(
                                insertedUri,
                                arrayOf(MediaStore.MediaColumns.IS_PENDING),
                                null,
                                null,
                                null
                            )?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val isPendingCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
                                    val isPendingVal = cursor.getInt(isPendingCol)
                                    if (isPendingVal == 0) {
                                        pendingStatusVerified = true
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        if (!pendingStatusVerified) {
                            contentResolver.delete(insertedUri, null, null)
                            return@withContext false
                        }

                        // Final verify after IS_PENDING = 0 to make absolutely sure it's fully readable and perfect
                        val finalVerified = cryptoManager.verifySavedUriIntegrity(insertedUri, originalSha256)
                        if (!finalVerified) {
                            contentResolver.delete(insertedUri, null, null)
                            return@withContext false
                        }
                    }
                    
                    // Cleanup everything after successful recovery export
                    viewModelScope.launch {
                        _uiState.value = LockScreenState.IDLE
                        performLockboxFailureCleanup()
                    }
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

    private suspend fun performLockboxFailureCleanup() {
        val fileDeleted = withContext(Dispatchers.IO) {
            cryptoManager.deleteEncryptedFile() &&
            try {
                val stagingDir = java.io.File(context.filesDir, "staging")
                if (stagingDir.exists() && stagingDir.isDirectory) {
                    stagingDir.listFiles()?.forEach { file ->
                        file.delete()
                    }
                }
                repository.deleteRecoveryManifest()
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
        if (!fileDeleted) {
            _cleanupFailed.value = true
            _uiState.value = LockScreenState.IDLE
            return
        }
        val prefsCleared = repository.clearLockSession()
        if (!prefsCleared) {
            _cleanupFailed.value = true
            _uiState.value = LockScreenState.IDLE
            return
        }
        _cleanupFailed.value = false
        _uiState.value = LockScreenState.IDLE
        _isStatusUnknown.value = false
    }

    fun completeAndClean() {
        cleanupBitmap()
        viewModelScope.launch {
            performLockboxFailureCleanup()
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
                        val rowsUpdated = contentResolver.update(insertedUri, updateValues, null, null)
                        if (rowsUpdated <= 0) {
                            contentResolver.delete(insertedUri, null, null)
                            return@withContext false
                        }

                        // Query verification for IS_PENDING
                        var pendingStatusVerified = false
                        try {
                            contentResolver.query(
                                insertedUri,
                                arrayOf(MediaStore.MediaColumns.IS_PENDING),
                                null,
                                null,
                                null
                            )?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val isPendingCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
                                    val isPendingVal = cursor.getInt(isPendingCol)
                                    if (isPendingVal == 0) {
                                        pendingStatusVerified = true
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        if (!pendingStatusVerified) {
                            contentResolver.delete(insertedUri, null, null)
                            return@withContext false
                        }

                        // Final verify after IS_PENDING = 0 to make absolutely sure it's fully readable and perfect
                        val finalVerified = cryptoManager.verifySavedUriIntegrity(insertedUri, originalSha256)
                        if (!finalVerified) {
                            contentResolver.delete(insertedUri, null, null)
                            return@withContext false
                        }
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
