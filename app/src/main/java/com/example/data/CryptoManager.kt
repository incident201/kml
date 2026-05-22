package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class CryptoManager(private val context: Context) {

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val lockFile = File(context.filesDir, "locked_image.enc")

    data class OriginalFileMeta(
        val displayName: String,
        val mimeType: String,
        val size: Long
    )

    fun queryOriginalFileMeta(uri: Uri): OriginalFileMeta {
        var displayName = "image_${System.currentTimeMillis()}.jpg"
        var mimeType = "image/jpeg"
        var size = -1L
        
        try {
            if (uri.scheme == "file") {
                val file = File(uri.path ?: "")
                if (file.exists()) {
                    displayName = file.name
                    size = file.length()
                }
            } else {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) {
                            cursor.getString(nameIndex)?.let { displayName = it }
                        }
                        if (sizeIndex != -1) {
                            size = cursor.getLong(sizeIndex)
                        }
                    }
                }
                context.contentResolver.getType(uri)?.let { mimeType = it }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // Final fallback: if size was not found or is 0, try streaming to count bytes
        if (size <= 0) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    var count = 0L
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (stream.read(buffer).also { read = it } != -1) {
                        count += read
                    }
                    size = count
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        if (size < 0) size = 0L
        
        // Derive mime from file extension if fallback was used and displayName is present
        if (mimeType == "image/jpeg" && displayName != "image_${System.currentTimeMillis()}.jpg") {
            val ext = displayName.substringAfterLast('.', "").lowercase()
            if (ext.isNotEmpty()) {
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                if (mime != null) {
                    mimeType = mime
                }
            }
        }
        
        return OriginalFileMeta(displayName, mimeType, size)
    }

    fun encryptAndSave(inputUri: Uri, originalSize: Long): Boolean {
        val lockFileTmp = File(context.filesDir, "locked_image.enc.tmp")
        
        return try {
            if (lockFileTmp.exists()) {
                lockFileTmp.delete()
            }
            val encryptedFileTmp = EncryptedFile.Builder(
                context,
                lockFileTmp,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()

            val openInput = context.contentResolver.openInputStream(inputUri) ?: return false
            openInput.use { inputStream ->
                encryptedFileTmp.openFileOutput().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            // Verify size/integrity of decrypted bytes immediately
            val verified = verifyFileIntegrity(lockFileTmp, originalSize)
            if (verified) {
                if (lockFile.exists()) {
                    lockFile.delete()
                }
                val renameSuccess = lockFileTmp.renameTo(lockFile)
                renameSuccess
            } else {
                if (lockFileTmp.exists()) {
                    lockFileTmp.delete()
                }
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (lockFileTmp.exists()) lockFileTmp.delete()
            false
        }
    }

    private fun verifyFileIntegrity(file: File, expectedSize: Long): Boolean {
        if (!file.exists()) return false
        return try {
            val encryptedFile = EncryptedFile.Builder(
                context,
                file,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()
            
            var decryptedSize = 0L
            encryptedFile.openFileInput().use { inputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    decryptedSize += bytesRead
                }
            }
            decryptedSize == expectedSize
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun verifySavedUriIntegrity(savedUri: Uri, originalSize: Long): Boolean {
        return try {
            var savedSize = 0L
            context.contentResolver.openInputStream(savedUri)?.use { inputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    savedSize += bytesRead
                }
            }
            savedSize == originalSize
        } catch (e: Exception) {
            e.printStackTrace()
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

    fun decryptAndStream(outputStream: OutputStream): Boolean {
        return try {
            if (!lockFile.exists()) return false
            val encryptedFile = EncryptedFile.Builder(
                context,
                lockFile,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()
            
            encryptedFile.openFileInput().use { inputStream ->
                inputStream.copyTo(outputStream)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun encryptedFileExists(): Boolean {
        return lockFile.exists()
    }

    fun deleteEncryptedFile() {
        if (lockFile.exists()) {
            lockFile.delete()
        }
        val lockFileTmp = File(context.filesDir, "locked_image.enc.tmp")
        if (lockFileTmp.exists()) {
            lockFileTmp.delete()
        }
    }
}
