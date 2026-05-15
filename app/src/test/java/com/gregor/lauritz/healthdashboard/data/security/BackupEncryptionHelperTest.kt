package com.gregor.lauritz.healthdashboard.data.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Arrays

class BackupEncryptionHelperTest {
    private val helper = BackupEncryptionHelper()
    private val password = "strong-backup-password"
    private val originalData = "This is a secret message to be encrypted in a ZIP file.".toByteArray()

    @Test
    fun `encrypt and decrypt should return original data`() {
        val encrypted = helper.encrypt(originalData, password)
        val decrypted = helper.decrypt(encrypted, password)

        assertArrayEquals(originalData, decrypted)
    }

    @Test
    fun `encrypted data should be different from original data`() {
        val encrypted = helper.encrypt(originalData, password)

        // Salt + Ciphertext should not be equal to original
        assertFalse("Encrypted data should not match original data", Arrays.equals(originalData, encrypted))
    }

    @Test
    fun `encrypting same data twice should produce different results due to random salt`() {
        val encrypted1 = helper.encrypt(originalData, password)
        val encrypted2 = helper.encrypt(originalData, password)

        assertFalse("Multiple encryptions should produce different results", Arrays.equals(encrypted1, encrypted2))
    }

    @Test
    fun `decrypting with wrong password should fail`() {
        val encrypted = helper.encrypt(originalData, password)

        assertThrows(Exception::class.java) {
            helper.decrypt(encrypted, "wrong-password")
        }
    }

    @Test
    fun `decrypting tampered data should fail`() {
        val encrypted = helper.encrypt(originalData, password)
        encrypted[encrypted.size - 1] = (encrypted[encrypted.size - 1] + 1).toByte()

        assertThrows(Exception::class.java) {
            helper.decrypt(encrypted, password)
        }
    }

    @Test
    fun `decrypting too short data should fail`() {
        val shortData = ByteArray(10)

        assertThrows(IllegalArgumentException::class.java) {
            helper.decrypt(shortData, password)
        }
    }
}
