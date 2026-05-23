package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.CryptoManager
import com.example.data.LockRepository
import com.example.data.TransactionState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExampleRobolectricTest {

    @Before
    fun setUp() {
        try {
            val clazz = Class.forName("android.security.keystore.AndroidKeyStoreProvider")
            val method = clazz.getMethod("install")
            method.invoke(null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Test
    fun testEncryptionAndRelaunchVerification() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        
        // 1. Create a dummy file to encrypt
        val stagingDir = File(context.filesDir, "staging")
        if (!stagingDir.exists()) stagingDir.mkdirs()
        val dummyFile = File(stagingDir, "test_captured.jpg")
        val contentBytes = "This is a dummy image content to be encrypted".toByteArray()
        dummyFile.writeBytes(contentBytes)
        
        val digest = MessageDigest.getInstance("SHA-256")
        val expectedSha = digest.digest(contentBytes).joinToString("") { "%02x".format(it) }
        
        // 2. Encrypt and save
        var cryptoManager = CryptoManager(context)
        var repository = LockRepository(context)
        
        val meta = cryptoManager.queryOriginalFileMeta(Uri.fromFile(dummyFile))
        assertNotNull(meta)
        assertEquals(expectedSha, meta?.sha256)
        
        repository.setTransactionState(TransactionState.ENCRYPTING)
        repository.saveOriginalSha256(expectedSha)
        
        val encryptSuccess = cryptoManager.encryptAndSave(Uri.fromFile(dummyFile), expectedSha)
        assertTrue(encryptSuccess)
        
        repository.setTransactionState(TransactionState.LOCKED)
        
        // Let's verify right away
        assertTrue(cryptoManager.recoverableEncryptedFileIsValid(expectedSha))
        
        // 3. Simulate Relaunch by recreating instances with a fresh context
        println("Simulating app restart...")
        val freshCryptoManager = CryptoManager(context)
        val freshRepository = LockRepository(context)
        
        val savedSha = freshRepository.getOriginalSha256()
        assertEquals(expectedSha, savedSha)
        
        val state = freshRepository.getTransactionState()
        assertEquals(TransactionState.LOCKED, state)
        
        val fileValid = freshCryptoManager.recoverableEncryptedFileIsValid(savedSha ?: "")
        assertTrue("Expected encrypted file to be valid on relaunch", fileValid)

        // Verify that lockbox.key and lockbox.key.tmp do not exist in filesDir
        val legacyKeyFile = File(context.filesDir, "lockbox.key")
        val legacyKeyFileTmp = File(context.filesDir, "lockbox.key.tmp")
        assertFalse("lockbox.key should not be created in filesDir", legacyKeyFile.exists())
        assertFalse("lockbox.key.tmp should not be created in filesDir", legacyKeyFileTmp.exists())
    }

    @Test
    fun testLockboxKeyDoesNotExistInFilesDirAfterEncryption() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        
        val stagingDir = File(context.filesDir, "staging")
        if (!stagingDir.exists()) stagingDir.mkdirs()
        val dummyFile = File(stagingDir, "test_key_exists_check.jpg")
        val contentBytes = "Some dummy content".toByteArray()
        dummyFile.writeBytes(contentBytes)
        
        val digest = MessageDigest.getInstance("SHA-256")
        val expectedSha = digest.digest(contentBytes).joinToString("") { "%02x".format(it) }
        
        val cryptoManager = CryptoManager(context)
        val encryptSuccess = cryptoManager.encryptAndSave(Uri.fromFile(dummyFile), expectedSha)
        assertTrue(encryptSuccess)
        
        val legacyKeyFile = File(context.filesDir, "lockbox.key")
        val legacyKeyFileTmp = File(context.filesDir, "lockbox.key.tmp")
        
        assertFalse("lockbox.key should not exist in filesDir after encryption using Keystore", legacyKeyFile.exists())
        assertFalse("lockbox.key.tmp should not exist in filesDir after encryption using Keystore", legacyKeyFileTmp.exists())
    }
}

