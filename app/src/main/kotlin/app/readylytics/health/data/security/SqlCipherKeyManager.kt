package app.readylytics.health.data.security

import android.content.Context
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
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages SQLCipher database encryption key generation, storage, and decryption.
 * Uses Android KeyStore to protect a 256-bit AES key, with encrypted key + IV stored in SharedPreferences.
 * Memory safety: transient plaintext ByteArray instances are zeroed with .fill(0) after use.
 * The raw SQLCipher password array is intentionally retained by SQLCipher's helper for the
 * helper's lifecycle, including later opens or connections, and therefore cannot be zeroed here.
 */
@Singleton
class SqlCipherKeyManager
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val keyProvider: KeyProvider,
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
         * Runs [block] holding both the JVM-wide [inProcessKeyLock] and the cross-process
         * advisory `FileLock` on the key marker file.
         *
         * NOT REENTRANT. Do not call this (directly or transitively) from within the [block]
         * passed to it: the outer [ReentrantLock] silently permits re-entry from the same
         * thread, but the inner [java.nio.channels.FileChannel.lock] on the freshly-opened
         * channel then throws [java.nio.channels.OverlappingFileLockException] (Java tracks
         * held file locks per-JVM, not per-channel, and has no reentrant semantics), so a
         * nested call fails loudly rather than deadlocking or blocking.
         */
        private fun <T> withCrossProcessKeyLock(block: () -> T): T {
            inProcessKeyLock.lock()
            try {
                val lockFile = File(context.filesDir, "sqlcipher_key.lock")
                RandomAccessFile(lockFile, "rw").use { raf ->
                    raf.channel.lock().use {
                        return block()
                    }
                }
            } finally {
                inProcessKeyLock.unlock()
            }
        }

        /**
         * Returns a SupportSQLiteOpenHelper.Factory configured with the database encryption key.
         * The key is passed as a raw hex string (x'...') to skip SQLCipher's default KDF and
         * use the 256-bit AES key directly.
         *
         * Key retrieval and SQLCipher delegate construction run inside [withCrossProcessKeyLock].
         * `delegate.create()` only constructs SQLCipher's lazy helper; its first readable or
         * writable accessor performs the physical open. The returned decorator therefore takes
         * the same lock around that first accessor too, which serializes Room's initial schema
         * write across processes. A database file's existence is not proof that this write has
         * completed (see KNOWN_ISSUE_sqlcipher_multiprocess_key_race.md).
         */
        fun getOrCreateFactory(dbFile: File): SupportSQLiteOpenHelper.Factory =
            SupportSQLiteOpenHelper.Factory { configuration ->
                val delegateHelper =
                    withCrossProcessKeyLock {
                        val decryptedKey =
                            try {
                                getOrCreateDbKeyLocked(dbFile)
                            } catch (e: KeyDecryptionException) {
                                // Key is corrupted. isKeyCorrupted StateFlow is already set to true
                                // by getOrCreateDbKeyLocked. Re-throw from within create() so Room's
                                // open fails visibly rather than proceeding with a bad key. The
                                // exception will propagate up through Room's infrastructure.
                                throw e
                            }

                        try {
                            val keyHex = decryptedKey.toHex()
                            val rawKeyBytes = "x'$keyHex'".toByteArray(Charsets.UTF_8)
                            // We must NOT fill rawKeyBytes with zeros: SupportOpenHelperFactory retains this
                            // array as the SQLCipher password for its helper's lifecycle, including later
                            // opens or connections. SQLCipher does not clear this caller-owned array for us.
                            val delegate =
                                net.zetetic.database.sqlcipher
                                    .SupportOpenHelperFactory(rawKeyBytes)
                            delegate.create(configuration)
                        } finally {
                            decryptedKey.fill(0)
                        }
                    }
                LockedFirstOpenHelper(delegateHelper)
            }

        private inner class LockedFirstOpenHelper(
            private val delegate: SupportSQLiteOpenHelper,
        ) : SupportSQLiteOpenHelper {
            private val openMonitor = Any()
            private var hasOpened = false

            override val databaseName: String?
                get() = delegate.databaseName

            override val writableDatabase: androidx.sqlite.db.SupportSQLiteDatabase
                get() = openOnce { delegate.writableDatabase }

            override val readableDatabase: androidx.sqlite.db.SupportSQLiteDatabase
                get() = openOnce { delegate.readableDatabase }

            override fun close() = delegate.close()

            override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
                delegate.setWriteAheadLoggingEnabled(enabled)
            }

            private fun <T> openOnce(open: () -> T): T =
                synchronized(openMonitor) {
                    if (hasOpened) return@synchronized open()
                    val database = withCrossProcessKeyLock(open)
                    hasOpened = true
                    database
                }
        }

        /**
         * Opens (creating if necessary) the SQLCipher database at [dbFile] and runs [block]
         * against it.
         *
         * Whether [block] itself runs inside [withCrossProcessKeyLock] depends on whether
         * [dbFile] already existed before this call:
         * - **File didn't exist yet:** key retrieval, `openDatabase(..., CREATE_IF_NECESSARY, ...)`,
         *   AND [block] all run inside the lock. SQLite/SQLCipher does not write page 1 (the
         *   salt/HMAC header) at open time -- it's written lazily on the *first write transaction*.
         *   So on a genuinely fresh file, releasing the lock right after `openDatabase()` and
         *   letting the caller's first write (e.g. `CREATE TABLE`) run unlocked would let two
         *   processes race that first write and tear page 1 -- the exact corruption this lock
         *   exists to prevent (see KNOWN_ISSUE_sqlcipher_multiprocess_key_race.md). This is the
         *   fresh-install `Application.onCreate()` scenario.
         * - **File already existed:** only key retrieval + `openDatabase()` run inside the lock;
         *   [block] runs after it's released. SQLite/SQLCipher's own WAL-mode cross-process
         *   locking is sufficient for an already-initialized file, so holding this lock through
         *   arbitrary caller work (e.g. `V7DatabaseMigrator`'s multi-thousand-row batch copies)
         *   would only add unnecessary cross-process contention.
         *
         * The existence check happens once, before the lock is acquired, so it reflects this
         * call's own view of the race: if another process wins the race to create the file while
         * this one is waiting on the lock, this call still (harmlessly) takes the safer
         * lock-through-[block] path it already committed to.
         */
        fun <T> withWritableDatabase(
            dbFile: File,
            block: (net.zetetic.database.sqlcipher.SQLiteDatabase) -> T,
        ): T {
            val fileExistedBeforeOpen = dbFile.exists()
            return if (fileExistedBeforeOpen) {
                val db =
                    withCrossProcessKeyLock {
                        val rawKey = getOrCreateDbKeyLocked(dbFile)
                        try {
                            openWritableDatabase(dbFile, rawKey)
                        } finally {
                            rawKey.fill(0)
                        }
                    }
                db.use(block)
            } else {
                withCrossProcessKeyLock {
                    val rawKey = getOrCreateDbKeyLocked(dbFile)
                    val db =
                        try {
                            openWritableDatabase(dbFile, rawKey)
                        } finally {
                            rawKey.fill(0)
                        }
                    db.use(block)
                }
            }
        }

        private fun openWritableDatabase(
            dbFile: File,
            rawKey: ByteArray,
        ): net.zetetic.database.sqlcipher.SQLiteDatabase {
            // Raw key must be passed as bytes, not a String: the String-password overload
            // derives a PBKDF2 key from the literal "x'hex'" text instead of recognizing it
            // as a raw-hex key, silently opening with the wrong key (see getOrCreateFactory).
            val rawKeyBytes = "x'${rawKey.toHex()}'".toByteArray(Charsets.UTF_8)
            // The convenience openOrCreateDatabase() overloads hardcode CREATE_IF_NECESSARY
            // only, so every open runs setWalModeFromConfiguration() without the WAL flag and
            // forcibly resets journal_mode back to the default (delete) -- even if a previous
            // session had explicitly set WAL. Passing ENABLE_WRITE_AHEAD_LOGGING here makes
            // WAL mode stick across every reopen, matching Room's own WRITE_AHEAD_LOGGING config.
            return net.zetetic.database.sqlcipher.SQLiteDatabase
                .openDatabase(
                    dbFile.absolutePath,
                    rawKeyBytes,
                    null,
                    net.zetetic.database.sqlcipher.SQLiteDatabase.CREATE_IF_NECESSARY or
                        net.zetetic.database.sqlcipher.SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
                    null,
                    CIPHER_COMPATIBILITY_HOOK,
                )
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
            withCrossProcessKeyLock {
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
        }

        /**
         * Clears the stored key and deletes the encrypted database, so the next open regenerates
         * both from scratch (the recovery path behind DatabaseRecoveryScreen).
         *
         * Holds the same cross-process lock as [getOrCreateDbKey]: the key *removal* needs the
         * identical treatment as the key *write*, otherwise a concurrent getOrCreateDbKey() in
         * another thread/process can interleave with a reset and read a stale key against an
         * already-deleted DB file (or vice versa). The removal is likewise `commit = true` so it
         * is durably on disk before the lock is released.
         */
        fun resetKeyAndDatabase(dbFile: File) {
            withCrossProcessKeyLock {
                _isKeyCorrupted.value = false
                prefs.edit(commit = true) {
                    remove(PREF_ENCRYPTED_KEY)
                    remove(PREF_IV)
                }
                if (dbFile.exists()) {
                    dbFile.delete()
                    File("${dbFile.absolutePath}-wal").delete()
                    File("${dbFile.absolutePath}-shm").delete()
                }
            }
        }

        private fun getOrCreateDbKey(dbFile: File? = null): ByteArray =
            withCrossProcessKeyLock { getOrCreateDbKeyLocked(dbFile) }

        /**
         * Unlocked core of [getOrCreateDbKey]. Callable only from inside a block already holding
         * [withCrossProcessKeyLock] (e.g. from [withWritableDatabase] or [getOrCreateFactory],
         * which need the key fetch and the subsequent physical file open covered by one single
         * lock acquisition) -- [withCrossProcessKeyLock] is documented non-reentrant, so calling
         * the locked [getOrCreateDbKey] from within an already-held lock would throw
         * [java.nio.channels.OverlappingFileLockException].
         */
        private fun getOrCreateDbKeyLocked(dbFile: File? = null): ByteArray =
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

        private fun getOrCreateKeystoreKey(): SecretKey = keyProvider.getOrCreateKey(KEYSTORE_ALIAS)

        private fun encryptAndStoreKey(rawKey: ByteArray) {
            val keystoreKey = getOrCreateKeystoreKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keystoreKey)
            val iv = cipher.iv
            val encryptedKey = cipher.doFinal(rawKey)

            // commit = true: must be durably on disk before the cross-process lock is released,
            // so a losing process's first (post-lock) read of this SharedPreferences file is
            // guaranteed to see it. The default apply() is async and gives no cross-process
            // ordering guarantee -- see withCrossProcessKeyLock's doc comment above.
            prefs.edit(commit = true) {
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

        @androidx.annotation.VisibleForTesting
        internal fun getOrCreateDbKeyForTest(dbFile: File? = null): ByteArray = getOrCreateDbKey(dbFile)

        companion object {
            private const val KEYSTORE_ALIAS = "sqlcipher_db_key"

            // Serializes the key critical section across threads in THIS process (so we never
            // attempt to acquire the cross-process FileLock twice concurrently from the same JVM,
            // which throws OverlappingFileLockException instead of blocking) and, inside that,
            // across OS processes via an advisory FileLock on a marker file in the app's private
            // (per-UID, not per-process) files dir. Without this, two processes racing
            // Application.onCreate() on a fresh install (e.g. the profileinstaller broadcast
            // process and the launcher activity process, which share the app's default process
            // since neither declares android:process) can each generate a different key and
            // concurrently create the SQLCipher DB file, corrupting it.
            //
            // Deliberately in the companion object (JVM-wide), NOT an instance field: correctness
            // must not depend on @Singleton DI scoping. Test and benchmark code hand-constructs
            // extra SqlCipherKeyManager instances in the same process (e.g.
            // V7DatabaseMigratorInstrumentedTest, V7DatabaseBenchmarkDriver); with a per-instance
            // lock those would not serialize against the app's own singleton, and both would race
            // to FileChannel.lock() the same marker file from one JVM -- which throws
            // OverlappingFileLockException rather than blocking. A static lock makes at most one
            // file-lock attempt in flight per process by construction, whatever the instance count.
            private val inProcessKeyLock = ReentrantLock()

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
