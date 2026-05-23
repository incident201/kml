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
    IDLE, LOCKING, LOCKED, UNLOCKED_PENDING_EXPORT, MISSING_FILE, DELETE_ORIGINAL_PENDING, PERSISTENCE_ERROR, TIME_SYNC_REQUIRED, EMERGENCY_RECOVERY, LOCK_FAILED_ORIGINAL_AVAILABLE
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

    private val _emergencyTimeLeftMs = MutableStateFlow(-1L)
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

    private val _lastErrorDetails = MutableStateFlow<String?>(null)
    val lastErrorDetails: StateFlow<String?> = _lastErrorDetails.asStateFlow()

    private val _pendingOriginalUri = MutableStateFlow<String?>(null)
    val pendingOriginalUri: StateFlow<String?> = _pendingOriginalUri.asStateFlow()

    private var timerJob: kotlinx.coroutines.Job? = null

    init {
        checkCurrentState()
    }

    private fun isInStagingDir(file: java.io.File): Boolean {
        val stagingDir = java.io.File(context.filesDir, "staging").canonicalFile
        val candidate = file.canonicalFile
        return candidate.path.startsWith(stagingDir.path + java.io.File.separator)
    }

    private fun syncParentDirectory(file: java.io.File) {
        var fd: java.io.FileDescriptor? = null
        try {
            val parent = file.parentFile ?: return
            fd = android.system.Os.open(parent.absolutePath, android.system.OsConstants.O_RDONLY, 0)
            android.system.Os.fsync(fd)
        } catch (e: Throwable) {
            e.printStackTrace()
        } finally {
            if (fd != null) {
                try {
                    android.system.Os.close(fd)
                } catch (ce: Throwable) {
                    ce.printStackTrace()
                }
            }
        }
    }

    fun discardCapturedStaging(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            if (uri.scheme != "file") return@launch
            val path = uri.path ?: return@launch
            val file = java.io.File(path)
            if (isInStagingDir(file)) {
                if (file.exists()) {
                    val deleted = file.delete()
                    if (deleted) {
                        syncParentDirectory(file)
                    }
                }
            }
        }
    }

    private suspend fun handleOriginalDeletedVerified(expectedSha256: String, plannedEnd: Long, durationMs: Long) {
        if (expectedSha256.isBlank() || plannedEnd <= 0L || durationMs <= 0L) {
            _lastErrorDetails.value =
                "Сбой восстановления сессии: отсутствуют SHA-256 или временные метаданные."
            _uiState.value = LockScreenState.MISSING_FILE
            return
        }

        val hasLockFile = withContext(Dispatchers.IO) {
            cryptoManager.recoverableEncryptedFileExists() &&
            cryptoManager.getLockboxCheckResult(expectedSha256) is com.example.data.LockboxCheckResult.Ok
        }
        if (!hasLockFile) {
            _lastErrorDetails.value = "Критическая ошибка: Зашифрованный файл (.enc) поврежден или отсутствует в ORIGINAL_DELETED_VERIFIED."
            _uiState.value = LockScreenState.MISSING_FILE
            return
        }

        val ntpTime = SntpClient.getCurrentTimeUtc()
        if (ntpTime == null) {
            _timeLeftMs.value = -1L
            _emergencyTimeLeftMs.value = -1L
            _uiState.value = LockScreenState.TIME_SYNC_REQUIRED
            return
        }

        val elapsedSinceStart = (ntpTime - (plannedEnd - durationMs)).coerceIn(0L, durationMs)
        val bootTime = SystemClock.elapsedRealtime() - elapsedSinceStart
        
        val saveSuccess = repository.saveLockSession(plannedEnd, bootTime, durationMs) &&
                          repository.setTransactionState(TransactionState.LOCKED)
        if (saveSuccess) {
            scheduleAlarm(plannedEnd, ntpTime)
            _uiState.value = LockScreenState.LOCKED
            startTimer()
        } else {
            _uiState.value = LockScreenState.PERSISTENCE_ERROR
        }
    }

    fun checkCurrentState() {
        viewModelScope.launch {
            val manifest = repository.getRecoveryManifest()
            val state = try { repository.getTransactionState() } catch (e: Exception) { TransactionState.IDLE }

            val originalSha256 = repository.getOriginalSha256() ?: manifest?.sha256 ?: ""
            var hasLockFile = withContext(Dispatchers.IO) {
                cryptoManager.recoverableEncryptedFileExists() &&
                cryptoManager.getLockboxCheckResult(originalSha256) is com.example.data.LockboxCheckResult.Ok
            }
            if (!hasLockFile && originalSha256.isNotEmpty()) {
                val repaired = tryRepairLockboxFromStaging(originalSha256)
                if (repaired) {
                    hasLockFile = true
                }
            }

            val originalUriStr = repository.getOriginalUri() ?: manifest?.originalUri ?: ""
            val originalStatus = if (originalUriStr.isNotEmpty()) checkOriginalStatus(Uri.parse(originalUriStr)) else OriginalStatus.DELETED
            if (originalStatus == OriginalStatus.EXISTS) {
                _pendingOriginalUri.value = originalUriStr
            } else {
                _pendingOriginalUri.value = null
            }

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
                    if (manifest != null && hasLockFile && originalStatus == OriginalStatus.DELETED) {
                        _uiState.value = LockScreenState.EMERGENCY_RECOVERY
                        startEmergencyTimer()
                    } else {
                        _uiState.value = LockScreenState.IDLE
                    }
                }
                TransactionState.LOCK_FAILED_ORIGINAL_AVAILABLE -> {
                    _isStatusUnknown.value = false
                    if (originalStatus == OriginalStatus.EXISTS) {
                        _lastErrorDetails.value = _lastErrorDetails.value ?: "Блокировка не завершена: защищённая копия отсутствует, оригинальный staging-файл сохранён."
                        cleanupCryptoArtifactsOnly()
                        _canCancelLock.value = true
                        _uiState.value = LockScreenState.LOCK_FAILED_ORIGINAL_AVAILABLE
                    } else {
                        _lastErrorDetails.value = "Критическая ошибка: Оригинальный файл отсутствует."
                        _uiState.value = LockScreenState.MISSING_FILE
                    }
                }
                TransactionState.ENCRYPTING -> {
                    _isStatusUnknown.value = false
                    if (originalStatus == OriginalStatus.EXISTS) {
                        if (hasLockFile && originalSha256.isNotEmpty()) {
                            _canCancelLock.value = true
                            repository.setTransactionState(TransactionState.ENCRYPTED_VERIFIED)
                            repository.setTransactionState(TransactionState.DELETE_ORIGINAL_PENDING)
                            _uiState.value = LockScreenState.DELETE_ORIGINAL_PENDING
                        } else {
                            _lastErrorDetails.value = "Блокировка не завершена, оригинальный staging-файл сохранён."
                            cleanupCryptoArtifactsOnly()
                            repository.setTransactionState(TransactionState.LOCK_FAILED_ORIGINAL_AVAILABLE)
                            _canCancelLock.value = true
                            _uiState.value = LockScreenState.LOCK_FAILED_ORIGINAL_AVAILABLE
                        }
                    } else {
                        if (hasLockFile && originalSha256.isNotEmpty()) {
                            val plannedEnd = repository.getPlannedEndTimeUtc().let { if (it > 0L) it else (manifest?.endTimeUtc ?: 0L) }
                            val duration = repository.getDurationMs().let { if (it > 0L) it else (manifest?.durationMs ?: 0L) }
                            if (plannedEnd > 0L && duration > 0L) {
                                repository.setTransactionState(TransactionState.ORIGINAL_DELETED_VERIFIED)
                                handleOriginalDeletedVerified(originalSha256, plannedEnd, duration)
                            } else {
                                _lastErrorDetails.value = "Сбой восстановления сессии: неверные временные метаданные."
                                _uiState.value = LockScreenState.MISSING_FILE
                            }
                        } else {
                            _lastErrorDetails.value = "Критическая ошибка: Оригинальный файл удален до завершения шифрования."
                            _uiState.value = LockScreenState.MISSING_FILE
                        }
                    }
                }
                TransactionState.ENCRYPTED_VERIFIED -> {
                    _isStatusUnknown.value = false
                    if (originalStatus == OriginalStatus.EXISTS) {
                        if (hasLockFile && originalSha256.isNotEmpty()) {
                            _canCancelLock.value = true
                            repository.setTransactionState(TransactionState.DELETE_ORIGINAL_PENDING)
                            _uiState.value = LockScreenState.DELETE_ORIGINAL_PENDING
                        } else {
                            _lastErrorDetails.value = "Блокировка не завершена: защищённая копия отсутствует, оригинальный staging-файл сохранён."
                            cleanupCryptoArtifactsOnly()
                            repository.setTransactionState(TransactionState.LOCK_FAILED_ORIGINAL_AVAILABLE)
                            _canCancelLock.value = true
                            _uiState.value = LockScreenState.LOCK_FAILED_ORIGINAL_AVAILABLE
                        }
                    } else {
                        if (hasLockFile && originalSha256.isNotEmpty()) {
                            val plannedEnd = repository.getPlannedEndTimeUtc().let { if (it > 0L) it else (manifest?.endTimeUtc ?: 0L) }
                            val duration = repository.getDurationMs().let { if (it > 0L) it else (manifest?.durationMs ?: 0L) }
                            repository.setTransactionState(TransactionState.ORIGINAL_DELETED_VERIFIED)
                            handleOriginalDeletedVerified(originalSha256, plannedEnd, duration)
                        } else {
                            _lastErrorDetails.value = "Критическая ошибка: Оригинал удален, зашифрованный файл отсутствует."
                            _uiState.value = LockScreenState.MISSING_FILE
                        }
                    }
                }
                TransactionState.DELETE_ORIGINAL_PENDING -> {
                    _isStatusUnknown.value = false
                    if (originalStatus == OriginalStatus.EXISTS) {
                        if (hasLockFile && originalSha256.isNotEmpty()) {
                            val deleted = deleteStagingOriginal(Uri.parse(originalUriStr))
                            if (deleted) {
                                val plannedEnd = repository.getPlannedEndTimeUtc().let { if (it > 0L) it else (manifest?.endTimeUtc ?: 0L) }
                                val duration = repository.getDurationMs().let { if (it > 0L) it else (manifest?.durationMs ?: 0L) }
                                repository.setTransactionState(TransactionState.ORIGINAL_DELETED_VERIFIED)
                                handleOriginalDeletedVerified(originalSha256, plannedEnd, duration)
                            } else {
                                _cleanupFailed.value = true
                                _canCancelLock.value = true
                                _uiState.value = LockScreenState.DELETE_ORIGINAL_PENDING
                            }
                        } else {
                            _lastErrorDetails.value = "Блокировка не завершена: защищённая копия отсутствует, оригинальный staging-файл сохранён."
                            cleanupCryptoArtifactsOnly()
                            repository.setTransactionState(TransactionState.LOCK_FAILED_ORIGINAL_AVAILABLE)
                            _canCancelLock.value = true
                            _uiState.value = LockScreenState.LOCK_FAILED_ORIGINAL_AVAILABLE
                        }
                    } else {
                        val plannedEnd = repository.getPlannedEndTimeUtc().let { if (it > 0L) it else (manifest?.endTimeUtc ?: 0L) }
                        val duration = repository.getDurationMs().let { if (it > 0L) it else (manifest?.durationMs ?: 0L) }
                        repository.setTransactionState(TransactionState.ORIGINAL_DELETED_VERIFIED)
                        handleOriginalDeletedVerified(originalSha256, plannedEnd, duration)
                    }
                }
                TransactionState.ORIGINAL_DELETED_VERIFIED -> {
                    _isStatusUnknown.value = false
                    val plannedEnd = repository.getPlannedEndTimeUtc().let { if (it > 0L) it else (manifest?.endTimeUtc ?: 0L) }
                    val duration = repository.getDurationMs().let { if (it > 0L) it else (manifest?.durationMs ?: 0L) }
                    handleOriginalDeletedVerified(originalSha256, plannedEnd, duration)
                }
                TransactionState.LOCKED -> {
                    if (originalSha256.isBlank() || !hasLockFile) {
                        _lastErrorDetails.value =
                            "LOCKED state is present, but required session data is missing. " +
                            "shaPresent=${originalSha256.isNotBlank()}, lockFileExists=$hasLockFile"
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
                    if (!hasLockFile) {
                        _lastErrorDetails.value = "Критическая ошибка: Файл локбокса (.enc) полностью отсутствует на диске."
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

    private suspend fun deleteStagingOriginal(uri: Uri): Boolean {
        if (uri.scheme != "file") return false

        val path = uri.path ?: return false
        val file = java.io.File(path)

        if (!isInStagingDir(file)) return false
        if (!file.exists()) return true

        return withContext(Dispatchers.IO) {
            try {
                val deleted = file.delete()
                if (deleted) {
                    syncParentDirectory(file)
                }
                !file.exists()
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    // Redundant transition helper functions removed to enforce handleOriginalDeletedVerified() as the single source of truth.

    suspend fun lockImage(uri: Uri, durationMinutes: Int) {
        _uiState.value = LockScreenState.LOCKING
        
        try {
            require(uri.scheme == "file") { "Only staging file URIs are supported" }
            val file = java.io.File(uri.path ?: throw IllegalArgumentException("Missing path"))
            require(isInStagingDir(file)) { "File is not in the staging directory" }
        } catch (e: Exception) {
            _lastErrorDetails.value = "Неверный URI исходного файла: ${e.message}"
            cleanupCryptoArtifactsOnly()
            _uiState.value = LockScreenState.IDLE
            return
        }

        // 1. Extract metadata
        val originalMeta = withContext(Dispatchers.IO) {
            cryptoManager.queryOriginalFileMeta(uri)
        }
        if (originalMeta == null) {
            _lastErrorDetails.value = "Не удалось прочитать метаданные исходного файла"
            cleanupCryptoArtifactsOnly()
            _uiState.value = LockScreenState.IDLE
            return
        }
        
        // 1b. Get NTP time
        val currentNtpTime = SntpClient.getCurrentTimeUtc()
        if (currentNtpTime == null) {
            _lastErrorDetails.value = "Не удалось проверить время по NTP до начала блокировки. Пожалуйста, проверьте подключение к сети и попробуйте заблокировать ещё раз."
            cleanupCryptoArtifactsOnly()
            _uiState.value = LockScreenState.IDLE
            return
        }
        val durationMs = durationMinutes * 60 * 1000L
        val plannedEndTimeUtc = currentNtpTime + durationMs

        // Expose pending original URI for UI
        _pendingOriginalUri.value = uri.toString()

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
            _lastErrorDetails.value = "Ошибка сохранения сессии при подготовке ENCRYPTING"
            cleanupCryptoArtifactsOnly()
            repository.setTransactionState(TransactionState.LOCK_FAILED_ORIGINAL_AVAILABLE)
            _canCancelLock.value = true
            _uiState.value = LockScreenState.LOCK_FAILED_ORIGINAL_AVAILABLE
            return
        }

        // 2. Encrypt & Save using SHA-256 integrity
        val encryptSuccess = withContext(Dispatchers.IO) { 
            cryptoManager.encryptAndSave(uri, originalMeta.sha256) 
        }
        if (!encryptSuccess) {
            _lastErrorDetails.value = "Ошибка шифрования или верификации зашифрованного файла"
            cleanupCryptoArtifactsOnly()
            repository.setTransactionState(TransactionState.LOCK_FAILED_ORIGINAL_AVAILABLE)
            _canCancelLock.value = true
            _uiState.value = LockScreenState.LOCK_FAILED_ORIGINAL_AVAILABLE
            return
        }

        // 3. Document verified encrypted state
        if (!repository.setTransactionState(TransactionState.ENCRYPTED_VERIFIED)) {
            _lastErrorDetails.value = "Ошибка при переходе в ENCRYPTED_VERIFIED"
            cleanupCryptoArtifactsOnly()
            repository.setTransactionState(TransactionState.LOCK_FAILED_ORIGINAL_AVAILABLE)
            _canCancelLock.value = true
            _uiState.value = LockScreenState.LOCK_FAILED_ORIGINAL_AVAILABLE
            return
        }

        // 4. Deletion flow transition
        if (!repository.setTransactionState(TransactionState.DELETE_ORIGINAL_PENDING)) {
            _lastErrorDetails.value = "Ошибка при переходе в DELETE_ORIGINAL_PENDING"
            cleanupCryptoArtifactsOnly()
            repository.setTransactionState(TransactionState.LOCK_FAILED_ORIGINAL_AVAILABLE)
            _canCancelLock.value = true
            _uiState.value = LockScreenState.LOCK_FAILED_ORIGINAL_AVAILABLE
            return
        }
        
        // 5. Delete staging file before transitioning to ORIGINAL_DELETED_VERIFIED
        val deleted = deleteStagingOriginal(uri)
        if (!deleted) {
            _lastErrorDetails.value = "Не удалось удалить исходный staging-файл"
            _cleanupFailed.value = true
            _canCancelLock.value = true
            _uiState.value = LockScreenState.DELETE_ORIGINAL_PENDING
            return
        }

        // 6. Post-deletion hard verification!
        val status = checkOriginalStatus(uri)
        if (status == OriginalStatus.DELETED) {
            _pendingOriginalUri.value = null
            if (!repository.setTransactionState(TransactionState.ORIGINAL_DELETED_VERIFIED)) {
                _lastErrorDetails.value = "Ошибка при сохранении состояния ORIGINAL_DELETED_VERIFIED"
                _uiState.value = LockScreenState.PERSISTENCE_ERROR
                return
            }
            handleOriginalDeletedVerified(originalMeta.sha256, plannedEndTimeUtc, durationMs)
        } else {
            _lastErrorDetails.value = "Ошибка верификации удаления оригинального файла"
            _cleanupFailed.value = true
            _canCancelLock.value = true
            _uiState.value = LockScreenState.DELETE_ORIGINAL_PENDING
        }
    }

    fun retryDeleteAndLock() {
        viewModelScope.launch {
            val originalUriStr = repository.getOriginalUri() ?: return@launch
            val originalUri = Uri.parse(originalUriStr)
            
            val originalSha256 = repository.getOriginalSha256() ?: ""
            val checkResult = withContext(Dispatchers.IO) {
                cryptoManager.getLockboxCheckResult(originalSha256)
            }
            var hasRecoverable = checkResult is com.example.data.LockboxCheckResult.Ok
            if (!hasRecoverable) {
                val repaired = tryRepairLockboxFromStaging(originalSha256)
                if (repaired) {
                    hasRecoverable = true
                }
            }
            if (!hasRecoverable) {
                val status = checkOriginalStatus(originalUri)
                if (status == OriginalStatus.EXISTS) {
                    _lastErrorDetails.value =
                        "Блокировка не завершена: защищённая копия отсутствует, оригинальный staging-файл сохранён."
                    cleanupLockboxArtifactsOnly()
                    _canCancelLock.value = true
                    _uiState.value = LockScreenState.LOCK_FAILED_ORIGINAL_AVAILABLE
                } else {
                    _lastErrorDetails.value = formatCheckResultError(checkResult)
                    _uiState.value = LockScreenState.MISSING_FILE
                }
                return@launch
            }

            _uiState.value = LockScreenState.LOCKING
            
            val deleted = deleteStagingOriginal(originalUri)
            if (deleted) {
                _canCancelLock.value = false
                _isStatusUnknown.value = false
                val durationMinutes = repository.getDurationMinutes()
                val durationMs = durationMinutes * 60 * 1000L
                var calculatedEndTime = repository.getPlannedEndTimeUtc()
                if (calculatedEndTime == 0L) {
                    val currentNtpTime = SntpClient.getCurrentTimeUtc()
                    if (currentNtpTime == null) {
                        _uiState.value = LockScreenState.TIME_SYNC_REQUIRED
                        return@launch
                    }
                    calculatedEndTime = currentNtpTime + durationMs
                }
                
                if (repository.setTransactionState(TransactionState.ORIGINAL_DELETED_VERIFIED)) {
                    handleOriginalDeletedVerified(originalSha256, calculatedEndTime, durationMs)
                } else {
                    _uiState.value = LockScreenState.PERSISTENCE_ERROR
                }
            } else {
                _cleanupFailed.value = true
                _canCancelLock.value = true
                _uiState.value = LockScreenState.DELETE_ORIGINAL_PENDING
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
                    val passedSinceLock = (repository.getDurationMs() - (endNtpUtx - ntpTime)).coerceIn(0L, repository.getDurationMs())
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
            val ntpTime = SntpClient.getCurrentTimeUtc() ?: return@withContext false
            if (ntpTime < manifest.endTimeUtc) {
                return@withContext false
            }
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

    private suspend fun cleanupLockboxArtifactsOnly(): Boolean {
        return withContext(Dispatchers.IO) {
            val encryptedDeleted = cryptoManager.deleteEncryptedFile()
            val manifestDeleted = repository.deleteRecoveryManifest()
            val prefsCleared = repository.clearLockSession()
            _isStatusUnknown.value = false
            encryptedDeleted && manifestDeleted && prefsCleared
        }
    }

    private suspend fun cleanupCryptoArtifactsOnly(): Boolean {
        return withContext(Dispatchers.IO) {
            val encryptedDeleted = cryptoManager.deleteEncryptedFile()
            val manifestDeleted = repository.deleteRecoveryManifest()
            _isStatusUnknown.value = false
            encryptedDeleted && manifestDeleted
        }
    }

    private suspend fun performLockboxFailureCleanup() {
        _pendingOriginalUri.value = null
        val fileDeleted = withContext(Dispatchers.IO) {
            val lockboxCleaned = cleanupLockboxArtifactsOnly()
            
            var stagingDeleted = true
            try {
                val stagingDir = java.io.File(context.filesDir, "staging")
                if (stagingDir.exists() && stagingDir.isDirectory) {
                    var anyStagingDel = false
                    stagingDir.listFiles()?.forEach { file ->
                        val del = file.delete()
                        if (!del) stagingDeleted = false else anyStagingDel = true
                    }
                    if (anyStagingDel) {
                        syncParentDirectory(java.io.File(stagingDir, "dummy"))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                stagingDeleted = false
            }
            
            lockboxCleaned && stagingDeleted
        }
        if (!fileDeleted) {
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
            if (repository.getTransactionState() != TransactionState.UNLOCKED_PENDING_EXPORT) {
                val ntpTime = SntpClient.getCurrentTimeUtc() ?: return@withContext false
                if (ntpTime < repository.getEndTimeUtc()) {
                    return@withContext false
                }
            }
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
            if (uri.scheme != "file") return@withContext OriginalStatus.UNKNOWN
            val filePath = uri.path ?: return@withContext OriginalStatus.UNKNOWN
            val file = java.io.File(filePath)
            if (!isInStagingDir(file)) {
                return@withContext OriginalStatus.UNKNOWN
            }

            if (file.exists()) {
                OriginalStatus.EXISTS
            } else {
                OriginalStatus.DELETED
            }
        }
    }

    private suspend fun tryRepairLockboxFromStaging(expectedSha256: String): Boolean {
        val originalUriStr = repository.getOriginalUri()
            ?: repository.getRecoveryManifest()?.originalUri
            ?: return false

        val uri = Uri.parse(originalUriStr)

        if (uri.scheme != "file") return false

        val path = uri.path ?: return false
        val file = java.io.File(path)

        if (!file.exists()) return false
        if (!isInStagingDir(file)) return false

        val stagingValid = withContext(Dispatchers.IO) {
            cryptoManager.verifySavedUriIntegrity(uri, expectedSha256)
        }

        if (!stagingValid) return false

        val encryptedAgain = withContext(Dispatchers.IO) {
            cryptoManager.encryptAndSave(uri, expectedSha256)
        }

        if (!encryptedAgain) return false

        val repairedCheck = withContext(Dispatchers.IO) {
            cryptoManager.getLockboxCheckResult(expectedSha256)
        }

        return repairedCheck is com.example.data.LockboxCheckResult.Ok
    }

    private fun formatCheckResultError(result: com.example.data.LockboxCheckResult): String {
        return when (result) {
            is com.example.data.LockboxCheckResult.FileMissing -> "Критическая ошибка: Файл локбокса (.enc) полностью отсутствует на диске."
            is com.example.data.LockboxCheckResult.ShaMissing -> "Системная ошибка: SHA-256 ожидаемого оригинала отсутствует в реестре."
            is com.example.data.LockboxCheckResult.DecryptFailed -> {
                val err = result.error
                "Сбой дешифрования Keystore/AES-GCM:\n${err.javaClass.simpleName}: ${err.message}\n" +
                        err.stackTrace?.joinToString("\n") { ste -> "\tat $ste" }
            }
            is com.example.data.LockboxCheckResult.HashMismatch -> {
                "Ошибка целостности данных (Контрольная сумма не совпадает):\nОжидалось: ${result.expected}\nФактически: ${result.actual}"
            }
            else -> "Неизвестный статус целостности"
        }
    }

    fun cancelPendingLock() {
        viewModelScope.launch {
            if ((_uiState.value == LockScreenState.DELETE_ORIGINAL_PENDING || _uiState.value == LockScreenState.LOCK_FAILED_ORIGINAL_AVAILABLE) && _canCancelLock.value) {
                cleanupLockboxArtifactsOnly()
                _pendingOriginalUri.value = null
                _uiState.value = LockScreenState.IDLE
            }
        }
    }

    fun retrySaveLockSessionAndTransition() {
        if (_uiState.value == LockScreenState.PERSISTENCE_ERROR) {
            viewModelScope.launch {
                val originalSha256 = repository.getOriginalSha256() ?: ""
                val plannedEnd = repository.getPlannedEndTimeUtc()
                val duration = repository.getDurationMs()
                
                val originalUriStr = repository.getOriginalUri()
                val originalStatus = if (!originalUriStr.isNullOrEmpty()) checkOriginalStatus(Uri.parse(originalUriStr)) else OriginalStatus.DELETED
                
                if (originalStatus == OriginalStatus.DELETED) {
                    handleOriginalDeletedVerified(originalSha256, plannedEnd, duration)
                } else {
                    _lastErrorDetails.value = "Сбой восстановления сессии: исходный файл всё ещё существует."
                    _uiState.value = LockScreenState.DELETE_ORIGINAL_PENDING
                }
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
