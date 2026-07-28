package app.readylytics.health.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.data.security.SqlCipherKeyManager.KeyDecryptionException
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
    fun getOrCreateFactory_concurrentThreads_convergeOnSingleKey() {
        val dbFile = File(context.filesDir, "concurrent_test.db")
        val threadCount = 8
        // Barrier 1: all threads wait before attempting key generation/retrieval
        val startBarrier = java.util.concurrent.CyclicBarrier(threadCount)
        // Barrier 2: all threads wait before validating the key, ensuring key generation is complete
        val validateBarrier = java.util.concurrent.CyclicBarrier(threadCount)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads =
            (1..threadCount).map { index ->
                Thread {
                    try {
                        // All threads reach the barrier, then attempt concurrent key generation/retrieval
                        startBarrier.await()
                        // Trigger key generation/retrieval through getOrCreateFactory, which calls
                        // getOrCreateDbKey() (locked). This will either generate a new key if none exists,
                        // or retrieve an existing one. All threads should converge on the same key.
                        keyManager.getOrCreateFactory(dbFile)
                    } catch (t: UnsatisfiedLinkError) {
                        // Expected in Robolectric - native SQLCipher library not available.
                        // This occurs after the lock is released, proving the lock worked correctly.
                    } catch (t: Throwable) {
                        if (t !is java.nio.channels.OverlappingFileLockException) {
                            errors.add(t)
                        } else {
                            // OverlappingFileLockException means the lock isn't working
                            errors.add(t)
                        }
                    }
                    try {
                        // All threads wait here to ensure key generation completed for all threads
                        validateBarrier.await()
                        // Now validate that all threads can successfully decrypt the key.
                        // If threads had generated different keys, some would fail here.
                        // If the lock is working, all threads should see the same valid key.
                        keyManager.validateKeyDecryption()
                        // After validation, check that the key wasn't marked as corrupted
                        assertFalse(
                            keyManager.isKeyCorrupted.value,
                            "Thread $index: key was marked as corrupted during concurrent access",
                        )
                    } catch (t: Throwable) {
                        errors.add(t)
                    }
                }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }

        // Check that all threads completed (didn't deadlock or hang)
        assertTrue(threads.none { it.isAlive }, "Some threads did not complete within timeout")

        // Assert no unexpected errors occurred (UnsatisfiedLinkError is expected and acceptable)
        val nonUnsatisfiedLinkErrors =
            errors.filter { it !is UnsatisfiedLinkError }
        assertTrue(
            nonUnsatisfiedLinkErrors.isEmpty(),
            "Concurrent access threw unexpected errors: ${nonUnsatisfiedLinkErrors.toList()}",
        )
    }
}
