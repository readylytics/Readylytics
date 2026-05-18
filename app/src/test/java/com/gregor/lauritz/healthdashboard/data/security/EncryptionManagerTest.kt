package com.gregor.lauritz.healthdashboard.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class EncryptionManagerTest {
    private lateinit var encryptionManager: EncryptionManager
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        encryptionManager = EncryptionManager(context)
    }

    @Test
    fun encrypt_thenDecrypt_roundTrip_returnsOriginal() {
        val original = "test backup password 12345"
        val encrypted = encryptionManager.encrypt(original)
        val decrypted = encryptionManager.decrypt(encrypted)

        assertTrue(decrypted.isNotEmpty(), "Decrypted value should not be empty")
        assertTrue(original == decrypted, "Decrypted should equal original: '$original' != '$decrypted'")
    }

    @Test
    fun encrypt_sameInput_twice_produceDifferentCiphertext() {
        val input = "same password for two encryptions"
        val encrypted1 = encryptionManager.encrypt(input)
        val encrypted2 = encryptionManager.encrypt(input)

        assertTrue(encrypted1.isNotEmpty(), "First encryption should not be empty")
        assertTrue(encrypted2.isNotEmpty(), "Second encryption should not be empty")
        assertNotEquals(
            encrypted1,
            encrypted2,
            "Same input encrypted twice should produce different ciphertexts (random IV)",
        )
    }

    @Test
    fun decrypt_corruptedInput_returnsNull() {
        val corrupted = "this is not a valid encrypted string at all"
        val result = encryptionManager.decrypt(corrupted)

        assertNull(result, "Decryption of corrupted input should return null")
    }
}
