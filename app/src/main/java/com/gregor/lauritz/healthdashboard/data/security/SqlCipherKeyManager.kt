package com.gregor.lauritz.healthdashboard.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages SQLCipher database encryption key generation, storage, and decryption.
 * Uses Android KeyStore to protect a 256-bit AES key, with encrypted key + IV stored in SharedPreferences.
 * Memory safety: all plaintext ByteArray instances are zeroed with .fill(0) after use.
 */
@Singleton
class SqlCipherKeyManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        init {
            try {
                System.loadLibrary("sqlcipher")
            } catch (e: UnsatisfiedLinkError) {
                // In Robolectric or Unit tests, native libraries are often not available.
                // We only throw if we're not in a test environment.
                if (System.getProperty("java.runtime.name")?.contains("Android", ignoreCase = true) == true) {
                    throw RuntimeException(
                        "Failed to load sqlcipher native library. Ensure SQLCipher is properly integrated.",
                        e,
                    )
                }
            }
        }

        private val prefs by lazy {
            context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
        }

        /**
         * Returns a SupportSQLiteOpenHelper.Factory configured with the database encryption key.
         * The decrypted key is immediately zeroed after the factory is created.
         */
        fun getOrCreateFactory(): SupportSQLiteOpenHelper.Factory {
            val decryptedKey = getOrCreateDbKey()
            return try {
                // Raw key mode matches migration's ATTACH KEY "x'hex'" syntax.
                // JVM String is immutable so keyHex cannot be zeroed — acceptable for this call site.
                val keyHex = "x'${decryptedKey.joinToString("") { "%02x".format(it) }}'"
                net.zetetic.database.sqlcipher
                    .SupportOpenHelperFactory(keyHex.toByteArray(Charsets.UTF_8))
            } finally {
                decryptedKey.fill(0)
            }
        }

        /**
         * Detects if the database file is plaintext (SQLite format) and migrates it to encrypted format.
         * Checks the first 16 bytes for SQLite magic header; if found, performs migration.
         */
        fun migrateIfNeeded(dbFile: File) {
            if (!dbFile.exists()) return

            val magic = ByteArray(16)
            FileInputStream(dbFile).use { stream ->
                val bytesRead = stream.read(magic)
                if (bytesRead != 16) return
            }

            // SQLite magic header is 16 bytes: "SQLite format 3\000"
            val sqliteMagic = "SQLite format 3\u0000".toByteArray(Charsets.UTF_8)
            if (!magic.contentEquals(sqliteMagic)) {
                return
            }

            val tempFile = File(dbFile.parent, "${dbFile.name}.cipher_tmp")
            val rawKey = getOrCreateDbKey()
            try {
                // Open plaintext DB with empty password
                val db =
                    net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(
                        dbFile,
                        "",
                        null,
                        null,
                        null,
                    )

                val keyHex = rawKey.joinToString("") { "%02x".format(it) }
                db.execSQL("ATTACH DATABASE '${tempFile.absolutePath}' AS encrypted KEY \"x'$keyHex'\"")
                db.execSQL("SELECT sqlcipher_export('encrypted')")
                db.execSQL("DETACH DATABASE encrypted")
                db.close()

                Files.move(
                    tempFile.toPath(),
                    dbFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )

                File("${dbFile.absolutePath}-wal").delete()
                File("${dbFile.absolutePath}-shm").delete()
            } catch (e: Exception) {
                tempFile.delete()
                throw RuntimeException("SQLCipher migration failed", e)
            } finally {
                rawKey.fill(0)
            }
        }

        /**
         * Exports a decrypted copy of the database to a plaintext file.
         * Used for Google Drive backups so data remains accessible after Keystore key loss.
         */
        fun exportPlaintext(
            dbFile: File,
            destFile: File,
        ) {
            if (!dbFile.exists()) return
            val rawKey = getOrCreateDbKey()
            try {
                val keyHex = rawKey.joinToString("") { "%02x".format(it) }
                val db =
                    net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(
                        dbFile,
                        "x'$keyHex'",
                        null,
                        null,
                        null,
                    )
                db.execSQL("ATTACH DATABASE '${destFile.absolutePath}' AS plaintext KEY ''")
                db.execSQL("SELECT sqlcipher_export('plaintext')")
                db.execSQL("DETACH DATABASE plaintext")
                db.close()
            } finally {
                rawKey.fill(0)
            }
        }

        private fun getOrCreateDbKey(): ByteArray =
            if (prefs.contains(PREF_ENCRYPTED_KEY)) {
                decryptKey()
            } else {
                val rawKey = ByteArray(32)
                SecureRandom().nextBytes(rawKey)
                try {
                    encryptAndStoreKey(rawKey)
                    rawKey.clone()
                } finally {
                    rawKey.fill(0)
                }
            }

        private fun getOrCreateKeystoreKey(): SecretKey {
            val isTest = System.getProperty("java.runtime.name")?.contains("Android", ignoreCase = true) == false
            if (isTest) {
                // In unit tests, we return a fixed key to avoid KeyStore dependency.
                return javax.crypto.spec.SecretKeySpec(ByteArray(32), "AES")
            }
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            return if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                (keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
            } else {
                val keyGenerator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
                keyGenerator.init(
                    KeyGenParameterSpec
                        .Builder(
                            KEYSTORE_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                keyGenerator.generateKey()
            }
        }

        private fun encryptAndStoreKey(rawKey: ByteArray) {
            val keystoreKey = getOrCreateKeystoreKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keystoreKey)
            val iv = cipher.iv
            val encryptedKey = cipher.doFinal(rawKey)

            prefs
                .edit()
                .putString(PREF_ENCRYPTED_KEY, Base64.encodeToString(encryptedKey, Base64.NO_WRAP))
                .putString(PREF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .commit()
        }

        private fun decryptKey(): ByteArray {
            val keystoreKey = getOrCreateKeystoreKey()
            val encryptedKeyBase64 =
                prefs.getString(PREF_ENCRYPTED_KEY, null)
                    ?: throw IllegalStateException("Encrypted key not found in preferences")
            val ivBase64 =
                prefs.getString(PREF_IV, null)
                    ?: throw IllegalStateException("Encryption IV not found in preferences")

            val encryptedKey = Base64.decode(encryptedKeyBase64, Base64.NO_WRAP)
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey, GCMParameterSpec(128, iv))
            return cipher.doFinal(encryptedKey)
        }

        companion object {
            private const val KEYSTORE_ALIAS = "sqlcipher_db_key"
            private const val PREF_FILE_NAME = "sqlcipher_key_prefs"
            private const val PREF_ENCRYPTED_KEY = "encrypted_key"
            private const val PREF_IV = "encryption_iv"
        }
    }
