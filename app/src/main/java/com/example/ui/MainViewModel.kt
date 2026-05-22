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
    IDLE, LOCKING, LOCKED, UNLOCKED_PENDING_EXPORT, MISSING_FILE, DELETE_ORIGINAL_PENDING
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

    private var timerJob: kotlinx.coroutines.Job? = null

    init {
        checkCurrentState()
    }

    fun checkCurrentState() {
        viewModelScope.launch {
            val state = repository.getTransactionState()
            when (state) {
                TransactionState.IDLE, TransactionState.CLEANED -> {
                    _uiState.value = LockScreenState.IDLE
                }
                TransactionState.ENCRYPTING, TransactionState.ENCRYPTED_VERIFIED -> {
                    // Safe cleanup - we didn't delete original yet
                    withContext(Dispatchers.IO) {
                        cryptoManager.deleteEncryptedFile()
                    }
                    repository.clearLockSession()
                    _uiState.value = LockScreenState.IDLE
                }
                TransactionState.DELETE_ORIGINAL_PENDING -> {
                    // Check if original file is still present
                    val originalUriStr = repository.getOriginalUri()
                    if (originalUriStr != null) {
                        val originalUri = Uri.parse(originalUriStr)
                        val exists = withContext(Dispatchers.IO) {
                            try {
                                context.contentResolver.openInputStream(originalUri)?.use { true } ?: false
                            } catch (e: Exception) {
                                false
                            }
                        }
                        if (exists) {
                            _uiState.value = LockScreenState.DELETE_ORIGINAL_PENDING
                        } else {
                            // Original already deleted, proceed to lock!
                            transitionToLockedState()
                        }
                    } else {
                        repository.clearLockSession()
                        _uiState.value = LockScreenState.IDLE
                    }
                }
                TransactionState.LOCKED -> {
                    _uiState.value = LockScreenState.LOCKED
                    startTimer()
                }
                TransactionState.UNLOCKED_PENDING_EXPORT -> {
                    _uiState.value = LockScreenState.UNLOCKED_PENDING_EXPORT
                    loadDecryptedBitmap()
                }
                TransactionState.RESTORED_VERIFIED -> {
                    completeAndClean()
                }
            }
        }
    }

    private fun transitionToLockedState() {
        val durationMinutes = repository.getDurationMinutes()
        val durationMs = durationMinutes * 60 * 1000L
        repository.setTransactionState(TransactionState.LOCKED)
        
        viewModelScope.launch {
            val currentNtpTime = SntpClient.getCurrentTimeUtc()
            if (currentNtpTime != null) {
                val endTimeUtc = currentNtpTime + durationMs
                val bootTime = SystemClock.elapsedRealtime()
                repository.saveLockSession(endTimeUtc, bootTime, durationMs)
                scheduleAlarm(endTimeUtc, currentNtpTime)
            } else {
                val endTimeUtc = System.currentTimeMillis() + durationMs
                val bootTime = SystemClock.elapsedRealtime()
                repository.saveLockSession(endTimeUtc, bootTime, durationMs)
            }
            _uiState.value = LockScreenState.LOCKED
            startTimer()
        }
    }

    fun lockImage(uri: Uri, durationMinutes: Int, onDeleteOriginal: suspend (Uri) -> Boolean) {
        viewModelScope.launch {
            _uiState.value = LockScreenState.LOCKING
            
            // 1. Extract metadata & set state to ENCRYPTING
            val originalMeta = withContext(Dispatchers.IO) {
                cryptoManager.queryOriginalFileMeta(uri)
            }
            
            repository.setTransactionState(TransactionState.ENCRYPTING)
            repository.saveOriginalMetadata(
                originalUri = uri.toString(),
                displayName = originalMeta.displayName,
                mimeType = originalMeta.mimeType,
                originalSize = originalMeta.size
            )
            repository.saveLockDuration(durationMinutes)

            // 1b. Get NTP time
            val currentNtpTime = SntpClient.getCurrentTimeUtc()
            if (currentNtpTime == null) {
                repository.clearLockSession()
                _uiState.value = LockScreenState.IDLE
                return@launch
            }

            // 2. Encrypt & Save with instant stream-based integrity check
            val success = withContext(Dispatchers.IO) { 
                cryptoManager.encryptAndSave(uri, originalMeta.size) 
            }
            if (!success) {
                repository.clearLockSession()
                _uiState.value = LockScreenState.IDLE
                return@launch
            }

            // 3. Document verified encrypted state
            repository.setTransactionState(TransactionState.ENCRYPTED_VERIFIED)

            // 4. Deletion flow transition
            repository.setTransactionState(TransactionState.DELETE_ORIGINAL_PENDING)
            val deleted = onDeleteOriginal(uri)
            if (!deleted) {
                withContext(Dispatchers.IO) { cryptoManager.deleteEncryptedFile() }
                repository.clearLockSession()
                _uiState.value = LockScreenState.IDLE
                return@launch
            }

            // 5. Success locking state
            val durationMs = durationMinutes * 60 * 1000L
            val endTimeUtc = currentNtpTime + durationMs
            val bootTime = SystemClock.elapsedRealtime()
            repository.saveLockSession(endTimeUtc, bootTime, durationMs)
            repository.setTransactionState(TransactionState.LOCKED)

            // 6. Schedule Alarm
            scheduleAlarm(endTimeUtc, currentNtpTime)

            _uiState.value = LockScreenState.LOCKED
            startTimer()
        }
    }

    fun retryDeleteAndLock(onDeleteOriginal: suspend (Uri) -> Boolean) {
        viewModelScope.launch {
            val originalUriStr = repository.getOriginalUri() ?: return@launch
            val originalUri = Uri.parse(originalUriStr)
            
            _uiState.value = LockScreenState.LOCKING
            val deleted = onDeleteOriginal(originalUri)
            if (deleted) {
                val currentNtpTime = SntpClient.getCurrentTimeUtc()
                val durationMinutes = repository.getDurationMinutes()
                val durationMs = durationMinutes * 60 * 1000L
                val bootTime = SystemClock.elapsedRealtime()
                
                if (currentNtpTime != null) {
                    val endTimeUtc = currentNtpTime + durationMs
                    repository.saveLockSession(endTimeUtc, bootTime, durationMs)
                    scheduleAlarm(endTimeUtc, currentNtpTime)
                } else {
                    val endTimeUtc = System.currentTimeMillis() + durationMs
                    repository.saveLockSession(endTimeUtc, bootTime, durationMs)
                }
                repository.setTransactionState(TransactionState.LOCKED)
                _uiState.value = LockScreenState.LOCKED
                startTimer()
            } else {
                _uiState.value = LockScreenState.DELETE_ORIGINAL_PENDING
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
            repository.setTransactionState(TransactionState.UNLOCKED_PENDING_EXPORT)
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
        viewModelScope.launch(Dispatchers.IO) {
            cryptoManager.deleteEncryptedFile()
            repository.clearLockSession()
            _uiState.value = LockScreenState.IDLE
        }
    }

    suspend fun restoreAndExport(): Boolean {
        return withContext(Dispatchers.IO) {
            val originalSize = repository.getOriginalSize()
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

                // Verify saved URI size and integrity
                val verified = cryptoManager.verifySavedUriIntegrity(insertedUri, originalSize)
                if (verified) {
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
