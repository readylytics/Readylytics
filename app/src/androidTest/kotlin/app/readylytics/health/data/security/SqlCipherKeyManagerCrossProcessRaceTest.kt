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
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var dbFile: File
    private val connections = mutableListOf<Pair<ServiceConnection, Messenger>>()

    @Before
    fun setUp() {
        dbFile = File(context.filesDir, "racetest_${System.nanoTime()}.db")
        File(context.filesDir, "sqlcipher_key.lock").delete()
        context
            .getSharedPreferences(SqlCipherKeyManager.PREF_FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        connections.forEach { (connection, _) -> context.unbindService(connection) }
        connections.clear()
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
    }

    @Test
    fun twoProcesses_raceOnFreshKey_bothSucceedAndConverge() {
        val process1Ready = CountDownLatch(1)
        val process2Ready = CountDownLatch(1)
        val process1Messenger =
            bindRaceService(KeyRaceTestServiceProcess1::class.java) { process1Ready.countDown() }
        val process2Messenger =
            bindRaceService(KeyRaceTestServiceProcess2::class.java) { process2Ready.countDown() }

        assertTrue("service 1 did not bind in time", process1Ready.await(10, TimeUnit.SECONDS))
        assertTrue("service 2 did not bind in time", process2Ready.await(10, TimeUnit.SECONDS))

        val doneLatch = CountDownLatch(2)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val replyMessenger =
            Messenger(
                Handler(Looper.getMainLooper()) { message ->
                    when (message.what) {
                        KeyRaceTestService.MSG_DONE -> doneLatch.countDown()
                        KeyRaceTestService.MSG_ERROR -> {
                            errors.add(message.data.getString("error"))
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
     */
    private fun bindRaceService(
        serviceClass: Class<out KeyRaceTestService>,
        onReady: () -> Unit,
    ): Messenger {
        var boundMessenger: Messenger? = null
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    binder: IBinder,
                ) {
                    boundMessenger = Messenger(binder)
                    onReady()
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
        val intent =
            Intent(context, serviceClass).apply {
                setPackage(context.packageName)
            }
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        connections.add(connection to Messenger(android.os.Binder()))
        while (boundMessenger == null) {
            Thread.sleep(10)
        }
        return boundMessenger!!
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
