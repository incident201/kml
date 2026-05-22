package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.LockRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val repository = LockRepository(context)
            if (repository.isLocked()) {
                repository.markReboot()
                // The app will check for NTP time on next open, since reboot clears the elapsedRealtime base
            }
        }
    }
}
