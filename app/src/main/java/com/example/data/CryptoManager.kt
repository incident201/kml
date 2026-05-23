package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed class LockboxCheckResult {
    data object Ok : LockboxCheckResult()
    data object FileMissing : LockboxCheckResult()
    data object ShaMissing : LockboxCheckResult()
    data class DecryptFailed(val error: Throwable) : LockboxCheckResult()
    data class HashMismatch(val expected: String, val actual: String) : LockboxCheckResult()
}

class CryptoManager(private val context: Context) {

    private val KEY_ALIAS = "kml_lockbox_aes_v1"
    private val ANDROID_KEYSTORE = "AndroidKeyStore"
    private val TRANSFORMATION = "AES/GCM/NoPadding"

    private val lockFile = File(context.filesDir, "locked_image.enc")

    data class OriginalFileMeta(
        val displayName: String,
        val mimeType: String,
        val size: Long,
        val sha256: String
    )

    private fun getSecretKey(createNewIfNeeded: Boolean): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
                entry?.secretKey
            } else if (createNewIfNeeded) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

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
            val secretKey = getSecretKey(createNewIfNeeded = true) ?: return false
            
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv

            val rawBytes = context.contentResolver.openInputStream(inputUri)?.use { it.readBytes() } ?: return false
            val ciphertext = cipher.doFinal(rawBytes)

            lockFileTmp.outputStream().use { fos ->
                // 1. Magic header "KML1"
                fos.write("KML1".toByteArray(Charsets.US_ASCII))
                // 2. IV length (4 bytes Big Endian)
                val ivLength = iv.size
                val ivLengthBuf = ByteBuffer.allocate(4).putInt(ivLength)
                fos.write(ivLengthBuf.array())
                // 3. IV bytes
                fos.write(iv)
                // 4. Ciphertext (which automatic-appends tag at end)
                fos.write(ciphertext)
                
                // Transactional write: ensure bytes are physically synchronized to disk via fsync
                fos.channel.force(true)
            }

            // Verify integrity of the temporary file immediately
            val verified = verifyFileIntegrity(lockFileTmp, expectedSha256)
            if (verified) {
                if (lockFile.exists() && !lockFile.delete()) {
                    lockFileTmp.delete()
                    return false
                }
                if (lockFileTmp.renameTo(lockFile)) {
                    true
                } else {
                    lockFileTmp.delete()
                    false
                }
            } else {
                if (lockFileTmp.exists()) {
                    lockFileTmp.delete()
                }
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (lockFileTmp.exists()) {
                lockFileTmp.delete()
            }
            false
        }
    }

    private fun decryptFileToBytes(file: File): ByteArray? {
        if (!file.exists()) return null
        return try {
            val secretKey = getSecretKey(createNewIfNeeded = false) 
                ?: throw IllegalStateException("Key $KEY_ALIAS not found in Android Keystore")

            file.inputStream().use { fis ->
                // 1. Read Magic header
                val header = ByteArray(4)
                if (fis.read(header) != 4 || String(header, Charsets.US_ASCII) != "KML1") {
                    throw IllegalArgumentException("Invalid file format header")
                }
                
                // 2. Read IV length
                val ivLengthBytes = ByteArray(4)
                if (fis.read(ivLengthBytes) != 4) {
                    throw IllegalArgumentException("Failed to read IV length")
                }
                val ivLength = ByteBuffer.wrap(ivLengthBytes).int

                // 3. Read IV bytes
                val iv = ByteArray(ivLength)
                if (fis.read(iv) != ivLength) {
                    throw IllegalArgumentException("Failed to read complete IV")
                }

                // 4. Read Ciphertext
                val ciphertext = fis.readBytes()

                // 5. Decrypt
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
                cipher.doFinal(ciphertext)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            lastVerifyException = e
            throw e
        }
    }

    fun verifyFileResult(file: File, expectedSha256: String?): LockboxCheckResult {
        if (!file.exists()) {
            return LockboxCheckResult.FileMissing
        }
        if (expectedSha256.isNullOrEmpty()) {
            return LockboxCheckResult.ShaMissing
        }
        return try {
            val decryptedBytes = decryptFileToBytes(file)
                ?: return LockboxCheckResult.DecryptFailed(NullPointerException("Decryption returned null"))
            
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(decryptedBytes)
            val calculatedSha256 = hashBytes.joinToString("") { "%02x".format(it) }
            
            if (calculatedSha256.equals(expectedSha256, ignoreCase = true)) {
                LockboxCheckResult.Ok
            } else {
                LockboxCheckResult.HashMismatch(expected = expectedSha256, actual = calculatedSha256)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            lastVerifyException = e
            LockboxCheckResult.DecryptFailed(e)
        }
    }

    private fun verifyFileIntegrity(file: File, expectedSha256: String): Boolean {
        return verifyFileResult(file, expectedSha256) is LockboxCheckResult.Ok
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
            val decryptedBytes = decryptFileToBytes(lockFile) ?: return null
            
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size, options)
            
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
            
            BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size, options)
        } catch (t: Throwable) {
            t.printStackTrace()
            null
        }
    }

    fun decryptAndStream(outputStream: OutputStream): Boolean {
        return try {
            if (!lockFile.exists()) return false
            val decryptedBytes = decryptFileToBytes(lockFile) ?: return false
            outputStream.write(decryptedBytes)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getLockboxCheckResult(expectedSha256: String): LockboxCheckResult {
        return verifyFileResult(lockFile, expectedSha256)
    }

    fun encryptedArtifactsExist(): Boolean {
        val lockFileTmp = File(context.filesDir, "locked_image.enc.tmp")
        return lockFile.exists() || lockFileTmp.exists()
    }

    fun recoverableEncryptedFileExists(): Boolean {
        return lockFile.exists()
    }

    fun recoverableEncryptedFileIsValid(expectedSha256: String): Boolean {
        return verifyFileResult(lockFile, expectedSha256) is LockboxCheckResult.Ok
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

    companion object {
        var lastVerifyException: Throwable? = null
    }
}
