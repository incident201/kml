package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
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

    private val TRANSFORMATION = "AES/GCM/NoPadding"

    private val lockFile = File(context.filesDir, "locked_image.enc")

    data class OriginalFileMeta(
        val displayName: String,
        val mimeType: String,
        val size: Long,
        val sha256: String
    )

    private val keyFile = File(context.filesDir, "lockbox.key")
    private val repository by lazy { LockRepository(context) }

    private fun getOrCreateSoftwareKey(): SecretKey {
        if (keyFile.exists()) {
            val bytes = keyFile.readBytes()
            if (bytes.size == 32) {
                return javax.crypto.spec.SecretKeySpec(bytes, "AES")
            }
            throw IllegalStateException("Lockbox key is corrupted")
        }

        if (lockFile.exists() || repository.isLocked()) {
            throw IllegalStateException("Lockbox key is missing while encrypted file exists")
        }

        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)

        val tmp = File(context.filesDir, "lockbox.key.tmp")
        try {
            java.io.FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (keyFile.exists() && !keyFile.delete()) {
                throw IllegalStateException("Failed to delete existing lockbox key file")
            }
            if (!tmp.renameTo(keyFile)) {
                tmp.delete()
                throw IllegalStateException("Failed to commit lockbox key file")
            }
            syncParentDirectory(keyFile)
        } catch (e: Exception) {
            if (tmp.exists()) tmp.delete()
            throw e
        }

        return javax.crypto.spec.SecretKeySpec(bytes, "AES")
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
        require(uri.scheme == "file") { "Only file scheme supported" }
        val filePath = uri.path ?: return null
        val file = File(filePath)
        
        val stagingDir = File(context.filesDir, "staging").canonicalFile
        val candidate = file.canonicalFile
        require(candidate.path.startsWith(stagingDir.path + File.separator)) { "File must reside inside staging folder" }
        
        if (!file.exists()) return null
        val displayName = file.name
        var mimeType = "image/jpeg"
        val ext = displayName.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            if (mime != null) {
                mimeType = mime
            }
        }
        
        val calc = try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var size = 0L
            file.inputStream().use { stream ->
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
        } ?: return null
        
        return OriginalFileMeta(displayName, mimeType, calc.second, calc.first)
    }

    fun encryptAndSave(inputUri: Uri, expectedSha256: String): Boolean {
        val lockFileTmp = File(context.filesDir, "locked_image.enc.tmp")
        if (lockFileTmp.exists() && !lockFileTmp.delete()) {
            return false
        }

        return try {
            val secretKey = getOrCreateSoftwareKey()
            
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv

            val fos = java.io.FileOutputStream(lockFileTmp)
            try {
                // 1. Magic header "KML1"
                fos.write("KML1".toByteArray(Charsets.US_ASCII))
                // 2. IV length (4 bytes Big Endian)
                val ivLength = iv.size
                val ivLengthBuf = ByteBuffer.allocate(4).putInt(ivLength)
                fos.write(ivLengthBuf.array())
                // 3. IV bytes
                fos.write(iv)
                
                // 4. Ciphertext (which automatic-appends tag at end) via CipherOutputStream
                val nonClosingFos = object : java.io.OutputStream() {
                    override fun write(b: Int) { fos.write(b) }
                    override fun write(b: ByteArray) { fos.write(b) }
                    override fun write(b: ByteArray, off: Int, len: Int) { fos.write(b, off, len) }
                    override fun flush() { fos.flush() }
                    override fun close() { fos.flush() }
                }
                
                javax.crypto.CipherOutputStream(nonClosingFos, cipher).use { cos ->
                    context.contentResolver.openInputStream(inputUri)?.use { inputStream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (inputStream.read(buffer).also { read = it } != -1) {
                            cos.write(buffer, 0, read)
                        }
                    } ?: throw java.io.IOException("Failed to open input stream")
                }
                
                fos.flush()
                // Transactional write: ensure bytes are physically synchronized to disk via fsync
                fos.fd.sync()
                fos.close()
            } catch (e: Exception) {
                try { fos.close() } catch (ex: Exception) {}
                throw e
            }

            if (lockFile.exists() && !lockFile.delete()) {
                lockFileTmp.delete()
                return false
            }
            if (lockFileTmp.renameTo(lockFile)) {
                syncParentDirectory(lockFile)
                val verified = verifyFileIntegrity(lockFile, expectedSha256)
                if (verified) {
                    true
                } else {
                    lockFile.delete()
                    false
                }
            } else {
                lockFileTmp.delete()
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
            val secretKey = getOrCreateSoftwareKey()

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

    fun verifyEncryptedFileByHash(file: File, expectedSha256: String): LockboxCheckResult {
        if (!file.exists()) {
            return LockboxCheckResult.FileMissing
        }
        if (expectedSha256.isEmpty()) {
            return LockboxCheckResult.ShaMissing
        }
        return try {
            val secretKey = getOrCreateSoftwareKey()
            val digest = java.security.MessageDigest.getInstance("SHA-256")

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

                // 4. Decrypt and hash
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
                
                javax.crypto.CipherInputStream(fis, cipher).use { cis ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (cis.read(buffer).also { read = it } != -1) {
                        digest.update(buffer, 0, read)
                    }
                }
            }
            
            val calculatedSha256 = digest.digest().joinToString("") { "%02x".format(it) }
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

    fun verifyFileResult(file: File, expectedSha256: String?): LockboxCheckResult {
        if (!file.exists()) {
            return LockboxCheckResult.FileMissing
        }
        if (expectedSha256.isNullOrEmpty()) {
            return LockboxCheckResult.ShaMissing
        }
        return verifyEncryptedFileByHash(file, expectedSha256)
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

    fun decryptToStream(outputStream: OutputStream): Boolean {
        if (!lockFile.exists()) return false
        return try {
            val secretKey = getOrCreateSoftwareKey()

            lockFile.inputStream().use { fis ->
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

                // 4. Decrypt via CipherInputStream
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
                
                javax.crypto.CipherInputStream(fis, cipher).use { cis ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (cis.read(buffer).also { read = it } != -1) {
                        outputStream.write(buffer, 0, read)
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            lastVerifyException = e
            false
        }
    }

    fun decryptAndStream(outputStream: OutputStream): Boolean {
        return decryptToStream(outputStream)
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
        var anyDeleted = false
        if (lockFile.exists()) {
            val deleted = lockFile.delete()
            if (!deleted || lockFile.exists()) success = false else anyDeleted = true
        }
        val lockFileTmp = File(context.filesDir, "locked_image.enc.tmp")
        if (lockFileTmp.exists()) {
            val deleted = lockFileTmp.delete()
            if (!deleted || lockFileTmp.exists()) success = false else anyDeleted = true
        }
        if (keyFile.exists()) {
            val deleted = keyFile.delete()
            if (!deleted || keyFile.exists()) success = false else anyDeleted = true
        }
        val keyFileTmp = File(context.filesDir, "lockbox.key.tmp")
        if (keyFileTmp.exists()) {
            val deleted = keyFileTmp.delete()
            if (!deleted || keyFileTmp.exists()) success = false else anyDeleted = true
        }
        if (anyDeleted) {
            syncParentDirectory(lockFile)
        }
        return success
    }

    private fun syncParentDirectory(file: File) {
        var fd: java.io.FileDescriptor? = null
        try {
            val parent = file.parentFile ?: return
            fd = android.system.Os.open(parent.absolutePath, android.system.OsConstants.O_RDONLY, 0)
            android.system.Os.fsync(fd)
        } catch (e: Throwable) {
            e.printStackTrace()
        } finally {
            if (fd != null) {
                try {
                    android.system.Os.close(fd)
                } catch (ce: Throwable) {
                    ce.printStackTrace()
                }
            }
        }
    }

    companion object {
        var lastVerifyException: Throwable? = null
    }
}
