package app.readylytics.health.data.security.racetest

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.readylytics.health.core.database.data.security.SqlCipherKeyManager
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Test-only Service (declared in app/src/debug/AndroidManifest.xml, driven from
 * SqlCipherKeyManagerCrossProcessRaceTest in app/src/androidTest) that runs SqlCipherKeyManager's
 * real getOrCreateDbKey() path in a genuinely separate OS process, driven by a Messenger.
 *
 * Lives in app/src/debug rather than app/src/androidTest: a `<service>` declared only in the
 * androidTest manifest is packaged as part of the separately-installed test APK, and a standalone
 * `android:process` launch of that APK (as opposed to test code loaded into the target app's
 * process via `am instrument`) doesn't carry a complete classpath (missing kotlin-stdlib),
 * crashing with `NoClassDefFoundError`. app/src/debug compiles into the real app APK for the
 * debug build type only -- never release or benchmark -- so this still never ships to production.
 *
 * Declared as an open base class with two subclasses ([KeyRaceTestServiceProcess1] /
 * [KeyRaceTestServiceProcess2]), one per `android:process` manifest entry, so the test can
 * target each specific process with an explicit `Intent(context, SubClass::class.java)`.
 * A single class declared under two manifest entries with the same `android:process`-less
 * class name cannot be reliably disambiguated by class-based Intent resolution -- Android
 * resolves the class name to whichever manifest entry it picks, not necessarily the one the
 * caller wants.
 *
 * Annotated @AndroidEntryPoint and injecting [SqlCipherKeyManager] rather than constructing one:
 * `android:process` only decides where the *component* runs, not which Application boots -- the
 * app's own @HiltAndroidApp HealthDashboardApplication.onCreate() runs first in every process and
 * eagerly builds DatabaseMigrationController, which immediately calls
 * DatabaseReadinessGate.inspect() -> SqlCipherKeyManager.withWritableDatabase() on the process's
 * @Singleton SqlCipherKeyManager. Injecting the singleton makes this service contend on exactly
 * the instance production code uses, which is what the cross-process assertion needs. (The
 * in-process half of the lock now lives in SqlCipherKeyManager's companion object, so a
 * hand-constructed second instance would no longer break mutual exclusion outright -- but it
 * would still be testing a different object than the one the app actually races with.)
 */
@AndroidEntryPoint
open class KeyRaceTestService : Service() {
    companion object {
        const val MSG_RUN = 1
        const val MSG_DONE = 2
        const val MSG_ERROR = 3
        const val MSG_READY = 4
        const val MSG_GO = 5
        const val EXTRA_DB_PATH = "db_path"
        const val EXTRA_WRITER_ID = "writer_id"
        const val EXTRA_OPEN_WITH_FACTORY = "open_with_factory"
    }

    /**
     * Injected by Hilt in the generated `Hilt_KeyRaceTestService.onCreate()`, i.e. from the
     * `super.onCreate()` call below -- always before any MSG_RUN can be handled.
     */
    @Inject
    lateinit var keyManager: SqlCipherKeyManager

    private lateinit var incomingMessenger: Messenger
    private var startGate: CountDownLatch? = null

    override fun onCreate() {
        super.onCreate()
        incomingMessenger =
            Messenger(
                Handler(Looper.getMainLooper()) { message ->
                    when (message.what) {
                        MSG_RUN -> {
                            handleRun(message)
                            true
                        }
                        MSG_GO -> {
                            startGate?.countDown()
                            true
                        }
                        else -> false
                    }
                },
            )
    }

    private fun handleRun(message: Message) {
        val replyTo = message.replyTo
        val data = message.data
        val dbPath = requireNotNull(data.getString(EXTRA_DB_PATH))
        val writerId = requireNotNull(data.getString(EXTRA_WRITER_ID))
        val openWithFactory = data.getBoolean(EXTRA_OPEN_WITH_FACTORY)
        val gate = CountDownLatch(1)
        startGate = gate
        Thread {
            try {
                if (openWithFactory) {
                    runFactoryOpen(dbPath, writerId, replyTo, gate)
                } else {
                    replyTo.send(Message.obtain(null, MSG_READY))
                    awaitStart(gate)
                    keyManager.withWritableDatabase(File(dbPath)) { database ->
                        database.execSQL("CREATE TABLE IF NOT EXISTS race_marker (writer TEXT)")
                        database.execSQL("INSERT INTO race_marker (writer) VALUES ('$writerId')")
                    }
                }
                replyTo.send(Message.obtain(null, MSG_DONE))
            } catch (t: Throwable) {
                val reply = Message.obtain(null, MSG_ERROR)
                reply.data = Bundle().apply { putString("error", "${t::class.simpleName}: ${t.message}") }
                replyTo.send(reply)
            }
        }.start()
    }

    private fun awaitStart(gate: CountDownLatch) {
        check(gate.await(10, TimeUnit.SECONDS)) { "Did not receive race start signal" }
    }

    private fun runFactoryOpen(
        dbPath: String,
        writerId: String,
        replyTo: Messenger,
        gate: CountDownLatch,
    ) {
        val helper =
            keyManager
                .getOrCreateFactory()
                .create(
                    SupportSQLiteOpenHelper.Configuration
                        .builder(this)
                        .name(dbPath)
                        .callback(
                            object : SupportSQLiteOpenHelper.Callback(1) {
                                override fun onCreate(db: SupportSQLiteDatabase) {
                                    db.execSQL("CREATE TABLE race_marker (writer TEXT)")
                                }

                                override fun onUpgrade(
                                    db: SupportSQLiteDatabase,
                                    oldVersion: Int,
                                    newVersion: Int,
                                ) = Unit
                            },
                        ).build(),
                )
        try {
            // Both services build their lazy helper first. Waiting here makes their first
            // writableDatabase accesses contend, so an unlocked lazy open cannot pass merely
            // because one service happened to run to completion before the other was sent.
            replyTo.send(Message.obtain(null, MSG_READY))
            awaitStart(gate)
            helper.writableDatabase.execSQL("INSERT INTO race_marker (writer) VALUES ('$writerId')")
        } finally {
            helper.close()
        }
    }

    override fun onBind(intent: Intent): IBinder = incomingMessenger.binder
}

/**
 * Runs in the `:racetest1` process (see app/src/debug/AndroidManifest.xml).
 *
 * Also annotated @AndroidEntryPoint: Hilt requires every class extending an @AndroidEntryPoint
 * base class to carry the annotation itself ("Classes that extend an @AndroidEntryPoint base
 * class must also be annotated @AndroidEntryPoint").
 */
@AndroidEntryPoint
class KeyRaceTestServiceProcess1 : KeyRaceTestService()

/** Runs in the `:racetest2` process (see app/src/debug/AndroidManifest.xml). */
@AndroidEntryPoint
class KeyRaceTestServiceProcess2 : KeyRaceTestService()
