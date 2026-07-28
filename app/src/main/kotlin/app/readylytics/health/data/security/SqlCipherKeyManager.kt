package app.readylytics.health.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.readylytics.health.domain.util.logE
import app.readylytics.health.domain.util.logW
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        @param:ApplicationContext private val context: Context,
    ) {
        init {
            try {
                System.loadLibrary("sqlcipher")
            } catch (e: UnsatisfiedLinkError) {
                logW("SqlCipherKeyManager", e) {
                    "Could not load sqlcipher library via System.loadLibrary"
                }
            }
        }

        private val _isKeyCorrupted = MutableStateFlow(false)
        val isKeyCorrupted: StateFlow<Boolean> = _isKeyCorrupted.asStateFlow()

        private val prefs by lazy {
            context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
        }

        /**
         * Returns a SupportSQLiteOpenHelper.Factory configured with the database encryption key.
         * The key is passed as a raw hex string (x'...') to skip SQLCipher's default KDF and
         * use the 256-bit AES key directly.
         */
        fun getOrCreateFactory(dbFile: File): SupportSQLiteOpenHelper.Factory {
            val decryptedKey = getOrCreateDbKey(dbFile)
            return try {
                val keyHex = decryptedKey.toHex()
                val rawKeyBytes = "x'$keyHex'".toByteArray(Charsets.UTF_8)
                // We must NOT fill rawKeyBytes with zeros here, because SupportOpenHelperFactory
                // holds a reference to the array and uses it when Room actually opens the database.
                // The factory clears the array automatically after the database is opened.
                net.zetetic.database.sqlcipher
                    .SupportOpenHelperFactory(rawKeyBytes)
            } finally {
                decryptedKey.fill(0)
            }
        }

        fun <T> withWritableDatabase(
            dbFile: File,
            block: (net.zetetic.database.sqlcipher.SQLiteDatabase) -> T,
        ): T {
            val rawKey = getOrCreateDbKey(dbFile)
            return try {
                // Raw key must be passed as bytes, not a String: the String-password overload
                // derives a PBKDF2 key from the literal "x'hex'" text instead of recognizing it
                // as a raw-hex key, silently opening with the wrong key (see getOrCreateFactory).
                val rawKeyBytes = "x'${rawKey.toHex()}'".toByteArray(Charsets.UTF_8)
                // The convenience openOrCreateDatabase() overloads hardcode CREATE_IF_NECESSARY
                // only, so every open runs setWalModeFromConfiguration() without the WAL flag and
                // forcibly resets journal_mode back to the default (delete) -- even if a previous
                // session had explicitly set WAL. Passing ENABLE_WRITE_AHEAD_LOGGING here makes
                // WAL mode stick across every reopen, matching Room's own WRITE_AHEAD_LOGGING config.
                net.zetetic.database.sqlcipher.SQLiteDatabase
                    .openDatabase(
                        dbFile.absolutePath,
                        rawKeyBytes,
                        null,
                        net.zetetic.database.sqlcipher.SQLiteDatabase.CREATE_IF_NECESSARY or
                            net.zetetic.database.sqlcipher.SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
                        null,
                        CIPHER_COMPATIBILITY_HOOK,
                    ).use(block)
            } finally {
                rawKey.fill(0)
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
            val rawKey = getOrCreateDbKey(dbFile)
            try {
                val rawKeyBytes = "x'${rawKey.toHex()}'".toByteArray(Charsets.UTF_8)
                // Create the encrypted target via the same nativeKey-backed open path used to
                // reopen it later (withWritableDatabase/getOrCreateFactory), instead of ATTACH's
                // KEY clause. ATTACH's KEY clause and the Java-level password/nativeKey() path are
                // separate native code paths in SQLCipher and are not guaranteed to agree on
                // cipher settings -- opening the target the same way it will always be reopened
                // guarantees self-consistency.
                val encryptedDb =
                    net.zetetic.database.sqlcipher.SQLiteDatabase
                        .openOrCreateDatabase(tempFile, rawKeyBytes, null, null, CIPHER_COMPATIBILITY_HOOK)
                encryptedDb.rawExecSQL("ATTACH DATABASE '${dbFile.absolutePath}' AS plaintext KEY ''")
                // Force rollback journaling on the attached source so its full contents are
                // visible to the export regardless of any pending WAL state from its creator.
                encryptedDb.rawExecSQL("PRAGMA plaintext.journal_mode = DELETE")
                encryptedDb.rawExecSQL("SELECT sqlcipher_export('main', 'plaintext')")
                encryptedDb.rawExecSQL("DETACH DATABASE plaintext")
                encryptedDb.close()

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
         * Used only as an intermediate for encrypted local backups.
         */
        fun exportPlaintext(
            dbFile: File,
            destFile: File,
        ) {
            if (!dbFile.exists()) return
            val rawKey = getOrCreateDbKey(null)
            try {
                // Raw key must be passed as bytes, not a String: see withWritableDatabase.
                val rawKeyBytes = "x'${rawKey.toHex()}'".toByteArray(Charsets.UTF_8)
                // ENABLE_WRITE_AHEAD_LOGGING here too: see withWritableDatabase. dbFile is the
                // live app database -- opening it without this flag would silently downgrade its
                // journal_mode from wal back to delete as a side effect of a backup export.
                val db =
                    net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
                        dbFile.absolutePath,
                        rawKeyBytes,
                        null,
                        net.zetetic.database.sqlcipher.SQLiteDatabase.CREATE_IF_NECESSARY or
                            net.zetetic.database.sqlcipher.SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
                        null,
                        CIPHER_COMPATIBILITY_HOOK,
                    )
                db.rawExecSQL("ATTACH DATABASE '${destFile.absolutePath}' AS plaintext KEY ''")
                // Force rollback journaling on the attached copy so sqlcipher_export's writes land
                // directly in destFile instead of a WAL side-file that DETACH won't checkpoint away.
                db.rawExecSQL("PRAGMA plaintext.journal_mode = DELETE")
                db.rawExecSQL("SELECT sqlcipher_export('plaintext')")
                db.rawExecSQL("DETACH DATABASE plaintext")
                db.close()
            } finally {
                rawKey.fill(0)
            }
        }

        class KeyDecryptionException(
            message: String,
            cause: Throwable? = null,
        ) : Exception(message, cause)

        fun validateKeyDecryption() {
            if (prefs.contains(PREF_ENCRYPTED_KEY)) {
                try {
                    val decrypted = decryptKey()
                    decrypted.fill(0)
                } catch (e: Exception) {
                    _isKeyCorrupted.value = true
                    throw KeyDecryptionException("Failed to decrypt SQLite database key from KeyStore", e)
                }
            }
        }

        fun resetKeyAndDatabase(dbFile: File) {
            _isKeyCorrupted.value = false
            prefs.edit {
                remove(PREF_ENCRYPTED_KEY)
                remove(PREF_IV)
            }
            if (dbFile.exists()) {
                dbFile.delete()
                File("${dbFile.absolutePath}-wal").delete()
                File("${dbFile.absolutePath}-shm").delete()
            }
        }

        private fun getOrCreateDbKey(dbFile: File? = null): ByteArray =
            if (prefs.contains(PREF_ENCRYPTED_KEY)) {
                try {
                    decryptKey()
                } catch (e: Exception) {
                    _isKeyCorrupted.value = true
                    logE("SqlCipherKeyManager", e) {
                        "Failed to decrypt database key. KeyStore key may have changed or data is corrupted."
                    }
                    throw KeyDecryptionException("Database key decryption failed", e)
                }
            } else {
                generateAndStoreNewKey()
            }

        private fun generateAndStoreNewKey(): ByteArray {
            val rawKey = ByteArray(32)
            SecureRandom().nextBytes(rawKey)
            try {
                encryptAndStoreKey(rawKey)
                return rawKey.clone()
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

            prefs.edit {
                putString(PREF_ENCRYPTED_KEY, Base64.encodeToString(encryptedKey, Base64.NO_WRAP))
                putString(PREF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            }
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

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        companion object {
            private const val KEYSTORE_ALIAS = "sqlcipher_db_key"
            @androidx.annotation.VisibleForTesting
            internal const val PREF_FILE_NAME = "sqlcipher_key_prefs"
            @androidx.annotation.VisibleForTesting
            internal const val PREF_ENCRYPTED_KEY = "encrypted_key"
            @androidx.annotation.VisibleForTesting
            internal const val PREF_IV = "encryption_iv"

            // SQLCipher 4 default; pinned explicitly so a freshly-ATTACHed database (which uses
            // the library's current default) and a reopened one (via the legacy
            // openOrCreateDatabase overloads, which can default to an older compatibility level)
            // always agree on page size / KDF / HMAC settings.
            private const val CIPHER_COMPATIBILITY = 4

            private val CIPHER_COMPATIBILITY_HOOK =
                object : net.zetetic.database.sqlcipher.SQLiteDatabaseHook {
                    override fun preKey(connection: net.zetetic.database.sqlcipher.SQLiteConnection) {
                        connection.execute("PRAGMA cipher_compatibility = $CIPHER_COMPATIBILITY", null, null)
                    }

                    override fun postKey(connection: net.zetetic.database.sqlcipher.SQLiteConnection) = Unit
                }
        }
    }
