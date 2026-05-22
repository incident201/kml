package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class TransactionState {
    IDLE,
    ENCRYPTING,
    ENCRYPTED_VERIFIED,
    DELETE_ORIGINAL_PENDING,
    LOCKED,
    UNLOCKED_PENDING_EXPORT,
    RESTORED_VERIFIED,
    CLEANED
}

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

    fun setTransactionState(state: TransactionState): Boolean {
        return sharedPreferences.edit().putString("transaction_state", state.name).commit()
    }

    fun getTransactionState(): TransactionState {
        val stateStr = sharedPreferences.getString("transaction_state", TransactionState.IDLE.name)
        return try {
            TransactionState.valueOf(stateStr ?: TransactionState.IDLE.name)
        } catch (e: Exception) {
            TransactionState.IDLE
        }
    }

    fun saveOriginalMetadata(originalUri: String, displayName: String?, mimeType: String?, originalSize: Long): Boolean {
        return sharedPreferences.edit()
            .putString("original_uri", originalUri)
            .putString("original_display_name", displayName)
            .putString("original_mime_type", mimeType)
            .putLong("original_size", originalSize)
            .commit()
    }

    fun getOriginalUri(): String? = sharedPreferences.getString("original_uri", null)
    fun getOriginalDisplayName(): String? = sharedPreferences.getString("original_display_name", null)
    fun getOriginalMimeType(): String? = sharedPreferences.getString("original_mime_type", null)
    fun getOriginalSize(): Long = sharedPreferences.getLong("original_size", 0)

    fun saveLockDuration(durationMinutes: Int): Boolean {
        return sharedPreferences.edit()
            .putInt("duration_minutes", durationMinutes)
            .commit()
    }

    fun getDurationMinutes(): Int = sharedPreferences.getInt("duration_minutes", 0)

    fun saveLockSession(endTimeUtc: Long, bootTimeAtLock: Long, durationMs: Long): Boolean {
        return sharedPreferences.edit()
            .putBoolean("is_locked", true)
            .putLong("end_time_utc", endTimeUtc)
            .putLong("boot_time_at_lock", bootTimeAtLock)
            .putLong("duration_ms", durationMs)
            .putBoolean("had_reboot", false) // New lock session resets reboot flag
            .commit()
    }

    fun markReboot() {
        if (isLocked()) {
            sharedPreferences.edit().putBoolean("had_reboot", true).commit()
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

    fun clearLockSession(): Boolean {
        return sharedPreferences.edit()
            .remove("is_locked")
            .remove("end_time_utc")
            .remove("boot_time_at_lock")
            .remove("duration_ms")
            .remove("had_reboot")
            .remove("transaction_state")
            .remove("original_uri")
            .remove("original_display_name")
            .remove("original_mime_type")
            .remove("original_size")
            .remove("duration_minutes")
            .commit()
    }
}
