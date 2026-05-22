package com.example.util

import android.content.Context
import androidx.security.crypto.MasterKey

object SecurityKeyProvider {
    @Volatile
    private var instance: MasterKey? = null

    fun getMasterKey(context: Context): MasterKey {
        return instance ?: synchronized(this) {
            instance ?: MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build().also { instance = it }
        }
    }
}
