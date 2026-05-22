package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.InputStream

class CryptoManager(private val context: Context) {

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val lockFile = File(context.filesDir, "locked_image.enc")

    fun encryptAndSave(inputUri: Uri): Boolean {
        return try {
            if (lockFile.exists()) {
                lockFile.delete()
            }
            val encryptedFile = EncryptedFile.Builder(
                context,
                lockFile,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()

            context.contentResolver.openInputStream(inputUri)?.use { inputStream ->
                encryptedFile.openFileOutput().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            if (lockFile.exists()) lockFile.delete()
            false
        }
    }

    fun decryptToMemory(): Bitmap? {
        return try {
            if (!lockFile.exists()) return null
            val encryptedFile = EncryptedFile.Builder(
                context,
                lockFile,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()
            
            encryptedFile.openFileInput().use { inputStream ->
                val bytes = inputStream.readBytes()
                val options = BitmapFactory.Options()
                options.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                
                var inSampleSize = 1
                val reqWidth = 1000
                val reqHeight = 1000
                if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                    val halfHeight: Int = options.outHeight / 2
                    val halfWidth: Int = options.outWidth / 2
                    while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                        inSampleSize *= 2
                    }
                }
                
                options.inJustDecodeBounds = false
                options.inSampleSize = inSampleSize
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun restoreToGallery(): ByteArray? {
        // Return byte array to be saved
        return try {
            if (!lockFile.exists()) return null
            val encryptedFile = EncryptedFile.Builder(
                context,
                lockFile,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()
            
            encryptedFile.openFileInput().use { inputStream ->
                inputStream.readBytes()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun deleteEncryptedFile() {
        if (lockFile.exists()) {
            lockFile.delete()
        }
    }
}
