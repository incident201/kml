package com.example.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.CryptoManager
import com.example.data.LockRepository
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
    IDLE, LOCKING, LOCKED, UNLOCKED, MISSING_FILE
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
        if (repository.isLocked()) {
            _uiState.value = LockScreenState.LOCKED
            startTimer()
        } else {
            _uiState.value = LockScreenState.IDLE
        }
    }

    fun lockImage(uri: Uri, durationMinutes: Int, onDeleteOriginal: suspend (Uri) -> Boolean) {
        viewModelScope.launch {
            _uiState.value = LockScreenState.LOCKING
            
            // 1. Get NTP Time
            val currentNtpTime = SntpClient.getCurrentTimeUtc()
            if (currentNtpTime == null) {
                // Failed to get time, fail lock
                _uiState.value = LockScreenState.IDLE
                return@launch
            }

            // 2. Encrypt
            val success = withContext(Dispatchers.IO) { cryptoManager.encryptAndSave(uri) }
            if (!success) {
                _uiState.value = LockScreenState.IDLE
                return@launch
            }

            // 3. Delete Original
            val deleted = onDeleteOriginal(uri)
            if (!deleted) {
                // If not deleted, abort
                withContext(Dispatchers.IO) { cryptoManager.deleteEncryptedFile() }
                _uiState.value = LockScreenState.IDLE
                return@launch
            }

            // 4. Save state
            val durationMs = durationMinutes * 60 * 1000L
            val endTimeUtc = currentNtpTime + durationMs
            val bootTime = SystemClock.elapsedRealtime()
            repository.saveLockSession(endTimeUtc, bootTime, durationMs)

            // 5. Schedule Alarm
            scheduleAlarm(endTimeUtc, currentNtpTime)

            _uiState.value = LockScreenState.LOCKED
            startTimer()
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

                    // If reboot occurred, we MUST use NTP.
                    if (repository.hadReboot()) {
                        val ntpTime = SntpClient.getCurrentTimeUtc()
                        if (ntpTime != null) {
                            hasValidTime = true
                            currentEstimateUtc = ntpTime
                            
                            // We successfully got NTP time, so we should calculate the difference
                            // and act as if we're offline based on this new boot time mark.
                            val currentBootTime = SystemClock.elapsedRealtime()
                            // Update repository boot time offset to this accurate boot time + elapsed
                            val passedSinceLock = repository.getDurationMs() - (endNtpUtx - ntpTime)
                            repository.saveLockSession(endNtpUtx, currentBootTime - passedSinceLock, repository.getDurationMs())
                        }
                    } else {
                        // We can use local offline elapsedRealtime for estimate
                        val currentBootTime = SystemClock.elapsedRealtime()
                        val passed = currentBootTime - bootAtLock
                        
                        // Offline estimate: endTimeUtc = startNtp + durationMs
                        // Start ntp = endTimeUtc - durationMs
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
        // Force an NTP check before unlocking just to be super sure.
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
            val bitmap = withContext(Dispatchers.IO) { cryptoManager.decryptToMemory() }
            if (bitmap == null) {
                _uiState.value = LockScreenState.MISSING_FILE
                repository.clearLockSession()
            } else {
                _unlockedBitmap.value = bitmap
                _uiState.value = LockScreenState.UNLOCKED
                // We do NOT clear lock session yet, so if app is restarted, we still know we are locked (but time is up, so it auto unlocks again)
            }
        }
    }

    fun loadDecryptedBitmap() {
        if (_uiState.value == LockScreenState.UNLOCKED && _unlockedBitmap.value == null) {
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
        }
        repository.clearLockSession()
        _uiState.value = LockScreenState.IDLE
    }

    fun getFileBytesToSave(): ByteArray? {
        return cryptoManager.restoreToGallery()
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
            } else { // Fallback
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
