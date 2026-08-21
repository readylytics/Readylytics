package app.readylytics.health.core.database.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.database.data.security.SqlCipherKeyManager.KeyDecryptionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import javax.crypto.SecretKey
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SqlCipherKeyManagerTest {
    private lateinit var keyManager: SqlCipherKeyManager
    private lateinit var fakeKeyProvider: FakeKeyProvider
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeKeyProvider : KeyProvider {
        var callCount = 0

        override fun getOrCreateKey(alias: String): SecretKey {
            callCount++
            return javax.crypto.spec.SecretKeySpec(ByteArray(32), "AES")
        }
    }

    @Before
    fun setUp() {
        fakeKeyProvider = FakeKeyProvider()
        keyManager = SqlCipherKeyManager(context, fakeKeyProvider)
    }

    private fun setupCorruptedPrefs() {
        context
            .getSharedPreferences(SqlCipherKeyManager.PREF_FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SqlCipherKeyManager.PREF_ENCRYPTED_KEY, "corrupted_base64_data")
            .putString(SqlCipherKeyManager.PREF_IV, "corrupted_iv_data")
            .commit()
    }

    @Test
    fun isKeyCorrupted_initiallyFalse() {
        assertFalse(keyManager.isKeyCorrupted.value)
    }

    @Test
    fun validateKeyDecryption_withCorruptedData_setsCorruptionState() {
        setupCorruptedPrefs()

        assertThrows(KeyDecryptionException::class.java) {
            keyManager.validateKeyDecryption()
        }

        assertTrue(keyManager.isKeyCorrupted.value)
    }

    @Test
    fun validateKeyDecryption_delegatesToKeyProvider() {
        setupCorruptedPrefs()

        assertThrows(KeyDecryptionException::class.java) {
            keyManager.validateKeyDecryption()
        }

        assertTrue(fakeKeyProvider.callCount > 0)
    }

    @Test
    fun resetKeyAndDatabase_clearsCorruptionState() {
        setupCorruptedPrefs()

        assertThrows(KeyDecryptionException::class.java) {
            keyManager.validateKeyDecryption()
        }

        assertTrue(keyManager.isKeyCorrupted.value)

        val dummyFile = File(context.filesDir, "dummy.db")
        keyManager.resetKeyAndDatabase(dummyFile)

        assertFalse(keyManager.isKeyCorrupted.value)
    }

    @Test
    fun getOrCreateFactory_withCorruptedKey_setsCorruptionState() {
        val prefs = context.getSharedPreferences(SqlCipherKeyManager.PREF_FILE_NAME, Context.MODE_PRIVATE)
        prefs
            .edit()
            .putString(SqlCipherKeyManager.PREF_ENCRYPTED_KEY, "corrupted")
            .putString(SqlCipherKeyManager.PREF_IV, "corrupted")
            .commit()

        val dbFile = File(context.filesDir, "test.db")

        // getOrCreateFactory should return a factory that throws during create()
        val factory = keyManager.getOrCreateFactory(dbFile)

        assertThrows(KeyDecryptionException::class.java) {
            val configuration =
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name("test.db")
                    .callback(
                        object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}

                            override fun onUpgrade(
                                db: androidx.sqlite.db.SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) {}
                        },
                    ).build()
            factory.create(configuration)
        }

        assertTrue(keyManager.isKeyCorrupted.value)
    }

    @Test
    fun getOrCreateDbKey_concurrentThreads_convergeOnSingleKey() {
        val dbFile = File(context.filesDir, "concurrent_test.db")
        val threadCount = 8
        val startBarrier = java.util.concurrent.CyclicBarrier(threadCount)
        val keys = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads =
            (1..threadCount).map { index ->
                Thread {
                    try {
                        startBarrier.await()
                        // All threads call getOrCreateDbKey() concurrently (protected by lock).
                        // Each thread either generates a new key if none exists, or retrieves
                        // an existing one. All threads should converge on the same key.
                        val key = keyManager.getOrCreateDbKeyForTest(dbFile)
                        keys.add(key)
                    } catch (t: Throwable) {
                        errors.add(t)
                    }
                }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }

        // Check that all threads completed (didn't deadlock or hang)
        assertTrue(threads.none { it.isAlive }, "Some threads did not complete within timeout")

        // Assert no exceptions occurred during key retrieval
        assertTrue(
            errors.isEmpty(),
            "Concurrent key access threw errors: ${errors.toList()}",
        )

        // Verify that all threads converged on the same key (byte-for-byte identical).
        // ByteArray doesn't override equals, so convert to List for comparison.
        val distinctKeys = keys.map { it.toList() }.distinct()
        assertEquals(
            "All concurrent callers must converge on a single key",
            1,
            distinctKeys.size,
        )

        // Verify we got exactly the expected number of keys (all threads succeeded)
        assertEquals("All threads should have returned a key", threadCount, keys.size)
    }
}
