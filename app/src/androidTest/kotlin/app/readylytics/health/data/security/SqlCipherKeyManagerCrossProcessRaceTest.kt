package app.readylytics.health.data.security

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.data.security.racetest.KeyRaceTestService
import app.readylytics.health.data.security.racetest.KeyRaceTestServiceProcess1
import app.readylytics.health.data.security.racetest.KeyRaceTestServiceProcess2
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Genuine two-OS-process regression test for the SqlCipherKeyManager cross-process key race
 * (see KNOWN_ISSUE_sqlcipher_multiprocess_key_race.md). Unlike Task 1's Robolectric test, which
 * proves the in-process ReentrantLock works within a single JVM, this drives two real Android
 * Service processes (`:racetest1` / `:racetest2`, declared in app/src/debug/AndroidManifest.xml
 * and compiled into the real debug app APK -- see KeyRaceTestService's doc comment for why they
 * live there rather than in this androidTest module) that both race to call
 * SqlCipherKeyManager.withWritableDatabase() on the same fresh DB file. Because the services are
 * part of the app-under-test's own package/UID, this test uses a single targetContext throughout:
 * these test-only processes share the app's UID/files-dir/Keystore with the app under test, so
 * they genuinely contend on the same lock file, SharedPreferences file, and Keystore alias
 * production code uses.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherKeyManagerCrossProcessRaceTest {
    private companion object {
        const val BIND_TIMEOUT_SECONDS = 10L
    }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var dbFile: File
    private val connections = mutableListOf<ServiceConnection>()

    // Snapshot of the REAL app's stored key, taken before this test clears it. These prefs are
    // the installed app's actual SQLCipher key storage (this test runs in the app's own UID), so
    // clearing them without restoring would leave the app's real health_dashboard.db permanently
    // undecryptable and strand the next launch on DatabaseRecoveryScreen.
    private var savedEncryptedKey: String? = null
    private var savedIv: String? = null

    private val keyPrefs
        get() = context.getSharedPreferences(SqlCipherKeyManager.PREF_FILE_NAME, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        dbFile = File(context.filesDir, "racetest_${System.nanoTime()}.db")
        // NOTE: deliberately does NOT delete the sqlcipher_key.lock marker file. On POSIX
        // filesystems unlinking a file does not release locks held on already-open descriptors,
        // and a later RandomAccessFile() on the same path yields a NEW inode -- so deleting it
        // mid-run can leave two "holders" locking different inodes with no mutual exclusion,
        // silently defeating the very lock under test (a false pass). The marker is a reusable
        // zero-byte file; there is nothing to clean up.
        savedEncryptedKey = keyPrefs.getString(SqlCipherKeyManager.PREF_ENCRYPTED_KEY, null)
        savedIv = keyPrefs.getString(SqlCipherKeyManager.PREF_IV, null)
        keyPrefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        // runCatching: unbindService throws IllegalArgumentException for a connection that never
        // actually bound (e.g. the test failed before onServiceConnected fired). Swallowing that
        // here keeps teardown from masking the real failure with a secondary exception.
        connections.forEach { connection -> runCatching { context.unbindService(connection) } }
        connections.clear()
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
        // Restore the real app's key (commit, not apply, so it is durably on disk before the
        // instrumentation process can be torn down).
        val editor = keyPrefs.edit()
        editor.clear()
        savedEncryptedKey?.let { editor.putString(SqlCipherKeyManager.PREF_ENCRYPTED_KEY, it) }
        savedIv?.let { editor.putString(SqlCipherKeyManager.PREF_IV, it) }
        editor.commit()
    }

    @Test
    fun twoProcesses_raceOnFreshKey_bothSucceedAndConverge() {
        val process1Messenger = bindRaceService(KeyRaceTestServiceProcess1::class.java, "service 1")
        val process2Messenger = bindRaceService(KeyRaceTestServiceProcess2::class.java, "service 2")

        val doneLatch = CountDownLatch(2)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val replyMessenger =
            Messenger(
                Handler(Looper.getMainLooper()) { message ->
                    when (message.what) {
                        KeyRaceTestService.MSG_DONE -> doneLatch.countDown()
                        KeyRaceTestService.MSG_ERROR -> {
                            // getString is platform-typed: a null here would NPE inside this
                            // main-thread Handler callback and crash the test process.
                            errors.add(message.data.getString("error") ?: "unknown error")
                            doneLatch.countDown()
                        }
                    }
                    true
                },
            )

        sendRun(process1Messenger, replyMessenger, writerId = "process1")
        sendRun(process2Messenger, replyMessenger, writerId = "process2")

        assertTrue("processes did not finish in time", doneLatch.await(15, TimeUnit.SECONDS))
        assertTrue("one or both processes failed: $errors", errors.isEmpty())

        val keyManager = SqlCipherKeyManager(context, AndroidKeystoreKeyProvider())
        keyManager.withWritableDatabase(dbFile) { database ->
            val count =
                database.rawQuery("SELECT COUNT(*) FROM race_marker", emptyArray()).use { cursor ->
                    cursor.moveToFirst()
                    cursor.getInt(0)
                }
            assertEquals("expected one row from each process, DB did not corrupt", 2, count)
        }
    }

    /**
     * Binds to [serviceClass] (either [KeyRaceTestServiceProcess1] or [KeyRaceTestServiceProcess2])
     * by its own concrete class rather than a shared base class + intent extra: the manifest
     * declares each subclass under its own `android:process` entry, so an explicit
     * `Intent(context, serviceClass)` deterministically targets the matching process.
     *
     * Waits on a [CountDownLatch] with a real [BIND_TIMEOUT_SECONDS] bound and fails the test
     * with [label] if binding never completes, so a regression that stops the service from
     * starting surfaces as a named assertion failure rather than an unbounded hang with no
     * diagnostic. The messenger is published through an [AtomicReference] because
     * `onServiceConnected` runs on the main thread while this method runs on the instrumentation
     * thread.
     */
    private fun bindRaceService(
        serviceClass: Class<out KeyRaceTestService>,
        label: String,
    ): Messenger {
        val messengerRef = AtomicReference<Messenger?>(null)
        val bound = CountDownLatch(1)
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    binder: IBinder,
                ) {
                    messengerRef.set(Messenger(binder))
                    bound.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
        val intent =
            Intent(context, serviceClass).apply {
                setPackage(context.packageName)
            }
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        connections.add(connection)
        assertTrue(
            "$label did not bind within $BIND_TIMEOUT_SECONDS s",
            bound.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        return requireNotNull(messengerRef.get()) { "$label bound but published no messenger" }
    }

    private fun sendRun(
        target: Messenger,
        replyTo: Messenger,
        writerId: String,
    ) {
        val message = Message.obtain(null, KeyRaceTestService.MSG_RUN)
        message.replyTo = replyTo
        message.data =
            Bundle().apply {
                putString(KeyRaceTestService.EXTRA_DB_PATH, dbFile.absolutePath)
                putString(KeyRaceTestService.EXTRA_WRITER_ID, writerId)
            }
        target.send(message)
    }
}
