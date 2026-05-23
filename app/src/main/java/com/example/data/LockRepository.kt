package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

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
        context.applicationContext.getSharedPreferences(
            "lock_prefs",
            Context.MODE_PRIVATE
        )
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

    fun saveOriginalSha256(sha256: String): Boolean {
        return sharedPreferences.edit().putString("original_sha256", sha256).commit()
    }

    fun getOriginalSha256(): String? = sharedPreferences.getString("original_sha256", null)

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

    fun savePlannedEndTimeUtc(plannedEndTimeUtc: Long): Boolean {
        return sharedPreferences.edit().putLong("planned_end_time_utc", plannedEndTimeUtc).commit()
    }

    fun getPlannedEndTimeUtc(): Long {
        return sharedPreferences.getLong("planned_end_time_utc", 0)
    }

    fun saveRecoveryManifest(
        originalUri: String,
        displayName: String,
        mimeType: String,
        originalSize: Long,
        sha256: String,
        endTimeUtc: Long,
        durationMs: Long
    ): Boolean {
        return try {
            val file = java.io.File(context.filesDir, "recovery_manifest.txt")
            val content = """
                original_uri=$originalUri
                original_display_name=$displayName
                original_mime_type=$mimeType
                original_size=$originalSize
                original_sha256=$sha256
                end_time_utc=$endTimeUtc
                duration_ms=$durationMs
            """.trimIndent()
            file.writeText(content)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    data class ManifestData(
        val originalUri: String,
        val displayName: String,
        val mimeType: String,
        val originalSize: Long,
        val sha256: String,
        val endTimeUtc: Long,
        val durationMs: Long
    )

    fun getRecoveryManifest(): ManifestData? {
        return try {
            val file = java.io.File(context.filesDir, "recovery_manifest.txt")
            if (!file.exists()) return null
            val lines = file.readLines()
            var uri = ""
            var name = ""
            var mime = ""
            var size = 0L
            var sha = ""
            var end = 0L
            var dur = 0L

            for (line in lines) {
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim()
                    when (key) {
                        "original_uri" -> uri = value
                        "original_display_name" -> name = value
                        "original_mime_type" -> mime = value
                        "original_size" -> size = value.toLongOrNull() ?: 0L
                        "original_sha256" -> sha = value
                        "end_time_utc" -> end = value.toLongOrNull() ?: 0L
                        "duration_ms" -> dur = value.toLongOrNull() ?: 0L
                    }
                }
            }
            if (sha.isNotEmpty()) {
                ManifestData(uri, name, mime, size, sha, end, dur)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun deleteRecoveryManifest(): Boolean {
        return try {
            val file = java.io.File(context.filesDir, "recovery_manifest.txt")
            if (file.exists()) {
                file.delete()
            } else {
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

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
            .remove("original_sha256")
            .remove("duration_minutes")
            .remove("planned_end_time_utc")
            .commit()
    }
}
