package com.gregor.lauritz.healthdashboard.data.security

import com.google.crypto.tink.subtle.AesGcmJce
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper for password-based encryption/decryption of backup files.
 * This implementation is portable and does not rely on the Android Keystore.
 * It uses PBKDF2 for key derivation and AES-GCM (via Tink) for encryption.
 */
@Singleton
class BackupEncryptionHelper
    @Inject
    constructor() {
        companion object {
            private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
            private const val SALT_SIZE_BYTES = 16
            private const val KEY_SIZE_BYTES = 32 // 256 bits for AES-256
            private const val PBKDF2_ITERATIONS = 100_000

            // Tag size for AES-GCM is typically 12 or 16 bytes. Tink's AesGcmJce uses 16 bytes.
        }

        /**
         * Encrypts the provided data using a password-derived key.
         * The output format is: [SALT (16 bytes)] + [CIPHERTEXT (including GCM tag)]
         */
        fun encrypt(
            data: ByteArray,
            password: String,
        ): ByteArray {
            val salt = generateSalt()
            val derivedKey = deriveKey(password, salt)

            return try {
                val aead = AesGcmJce(derivedKey)
                // Associated data is null for ZIP encryption unless we want to bind it to a specific ID
                val ciphertext = aead.encrypt(data, null)

                val result = ByteArray(salt.size + ciphertext.size)
                System.arraycopy(salt, 0, result, 0, salt.size)
                System.arraycopy(ciphertext, 0, result, salt.size, ciphertext.size)
                result
            } finally {
                zeroOut(derivedKey)
            }
        }

        /**
         * Decrypts the provided data using a password-derived key.
         * The input format must be: [SALT (16 bytes)] + [CIPHERTEXT (including GCM tag)]
         */
        fun decrypt(
            data: ByteArray,
            password: String,
        ): ByteArray {
            require(data.size > SALT_SIZE_BYTES) { "Invalid encrypted data: too short" }

            val salt = data.copyOfRange(0, SALT_SIZE_BYTES)
            val ciphertext = data.copyOfRange(SALT_SIZE_BYTES, data.size)
            val derivedKey = deriveKey(password, salt)

            return try {
                val aead = AesGcmJce(derivedKey)
                aead.decrypt(ciphertext, null)
            } finally {
                zeroOut(derivedKey)
            }
        }

        /**
         * Derives a 256-bit key from a password and salt using PBKDF2.
         */
        private fun deriveKey(
            password: String,
            salt: ByteArray,
        ): ByteArray {
            val passwordChars = password.toCharArray()
            val spec =
                PBEKeySpec(
                    passwordChars,
                    salt,
                    PBKDF2_ITERATIONS,
                    KEY_SIZE_BYTES * 8,
                )

            return try {
                val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
                factory.generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
                Arrays.fill(passwordChars, '\u0000')
            }
        }

        /**
         * Generates a random salt.
         */
        private fun generateSalt(): ByteArray {
            val random = SecureRandom()
            val salt = ByteArray(SALT_SIZE_BYTES)
            random.nextBytes(salt)
            return salt
        }

        /**
         * Securely zeros out a byte array to minimize the time sensitive data stays in memory.
         */
        private fun zeroOut(array: ByteArray) {
            Arrays.fill(array, 0.toByte())
        }
    }
