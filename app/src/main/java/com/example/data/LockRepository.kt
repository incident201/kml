package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class LockRepository(private val context: Context) {

    private val sharedPreferences: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "lock_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e("LockRepository", "Failed to use EncryptedSharedPreferences", e)
            context.getSharedPreferences("lock_prefs_fallback", Context.MODE_PRIVATE)
        }
    }

    fun saveLockSession(endTimeUtc: Long, bootTimeAtLock: Long, durationMs: Long) {
        sharedPreferences.edit()
            .putBoolean("is_locked", true)
            .putLong("end_time_utc", endTimeUtc)
            .putLong("boot_time_at_lock", bootTimeAtLock)
            .putLong("duration_ms", durationMs)
            .putBoolean("had_reboot", false) // New lock session resets reboot flag
            .apply()
    }

    fun markReboot() {
        if (isLocked()) {
            sharedPreferences.edit().putBoolean("had_reboot", true).apply()
        }
    }

    fun isLocked(): Boolean {
        return sharedPreferences.getBoolean("is_locked", false)
    }
    
    fun hadReboot(): Boolean {
        return sharedPreferences.getBoolean("had_reboot", false)
    }

    fun getEndTimeUtc(): Long {
        return sharedPreferences.getLong("end_time_utc", 0)
    }

    fun getBootTimeAtLock(): Long {
        return sharedPreferences.getLong("boot_time_at_lock", 0)
    }

    fun getDurationMs(): Long {
        return sharedPreferences.getLong("duration_ms", 0)
    }

    fun clearLockSession() {
        sharedPreferences.edit()
            .remove("is_locked")
            .remove("end_time_utc")
            .remove("boot_time_at_lock")
            .remove("duration_ms")
            .remove("had_reboot")
            .apply()
    }
}
