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
        val size: Long,
        val sha256: String
    )

    private fun calculateSha256AndSize(uri: Uri): Pair<String, Long>? {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var size = 0L
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(8192)
                var read: Int
                while (stream.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                    size += read
                }
                val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                Pair(sha256, size)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun queryOriginalFileMeta(uri: Uri): OriginalFileMeta? {
        val defaultDisplayName = "image_${System.currentTimeMillis()}.jpg"
        var displayName = defaultDisplayName
        var mimeType = "image/jpeg"
        
        try {
            if (uri.scheme == "file") {
                val file = File(uri.path ?: "")
                if (file.exists()) {
                    displayName = file.name
                }
            } else {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) {
                            cursor.getString(nameIndex)?.let { displayName = it }
                        }
                    }
                }
                context.contentResolver.getType(uri)?.let { mimeType = it }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        val calc = calculateSha256AndSize(uri) ?: return null
        val sha256 = calc.first
        val size = calc.second
        
        // Derive mime from file extension if fallback was used and displayName is present
        if (mimeType == "image/jpeg" && displayName != defaultDisplayName) {
            val ext = displayName.substringAfterLast('.', "").lowercase()
            if (ext.isNotEmpty()) {
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                if (mime != null) {
                    mimeType = mime
                }
            }
        }
        
        return OriginalFileMeta(displayName, mimeType, size, sha256)
    }

    fun encryptAndSave(inputUri: Uri, expectedSha256: String): Boolean {
        val lockFileTmp = File(context.filesDir, "locked_image.enc.tmp")
        if (lockFileTmp.exists() && !lockFileTmp.delete()) {
            return false
        }

        return try {
            if (lockFile.exists() && !lockFile.delete()) {
                return false
            }
            val encryptedFile = EncryptedFile.Builder(
                context,
                lockFile,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()

            val openInput = context.contentResolver.openInputStream(inputUri) ?: return false
            openInput.use { inputStream ->
                encryptedFile.openFileOutput().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            // Verify sha256/integrity of decrypted bytes immediately
            val verified = verifyFileIntegrity(lockFile, expectedSha256)
            if (verified) {
                true
            } else {
                if (lockFile.exists()) {
                    lockFile.delete()
                }
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (lockFile.exists()) {
                lockFile.delete()
            }
            false
        }
    }

    private fun verifyFileIntegrity(file: File, expectedSha256: String): Boolean {
        if (!file.exists()) return false
        return try {
            val encryptedFile = EncryptedFile.Builder(
                context,
                file,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()
            
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            encryptedFile.openFileInput().use { inputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val calculatedSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            calculatedSha256.equals(expectedSha256, ignoreCase = true)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun verifySavedUriIntegrity(savedUri: Uri, expectedSha256: String): Boolean {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(savedUri)?.use { inputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val calculatedSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            calculatedSha256.equals(expectedSha256, ignoreCase = true)
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
            
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            encryptedFile.openFileInput().use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }
            
            var inSampleSize = 1
            val reqWidth = 800
            val reqHeight = 800
            if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                val halfHeight: Int = options.outHeight / 2
                val halfWidth: Int = options.outWidth / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            
            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            
            encryptedFile.openFileInput().use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }
        } catch (t: Throwable) {
            t.printStackTrace()
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

    fun encryptedArtifactsExist(): Boolean {
        val lockFileTmp = File(context.filesDir, "locked_image.enc.tmp")
        return lockFile.exists() || lockFileTmp.exists()
    }

    fun recoverableEncryptedFileExists(): Boolean {
        return lockFile.exists()
    }

    fun recoverableEncryptedFileIsValid(expectedSha256: String): Boolean {
        return verifyFileIntegrity(lockFile, expectedSha256)
    }

    fun deleteEncryptedFile(): Boolean {
        var success = true
        if (lockFile.exists()) {
            success = lockFile.delete() && !lockFile.exists()
        }
        val lockFileTmp = File(context.filesDir, "locked_image.enc.tmp")
        if (lockFileTmp.exists()) {
            success = lockFileTmp.delete() && !lockFileTmp.exists() && success
        }
        return success
    }
}
