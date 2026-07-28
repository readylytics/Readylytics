package app.readylytics.health.data.security.racetest

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import app.readylytics.health.data.security.AndroidKeystoreKeyProvider
import app.readylytics.health.data.security.SqlCipherKeyManager
import java.io.File

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
 */
open class KeyRaceTestService : Service() {
    companion object {
        const val MSG_RUN = 1
        const val MSG_DONE = 2
        const val MSG_ERROR = 3
        const val EXTRA_DB_PATH = "db_path"
        const val EXTRA_WRITER_ID = "writer_id"
    }

    private lateinit var incomingMessenger: Messenger

    override fun onCreate() {
        super.onCreate()
        incomingMessenger =
            Messenger(
                Handler(Looper.getMainLooper()) { message ->
                    if (message.what == MSG_RUN) {
                        handleRun(message)
                        true
                    } else {
                        false
                    }
                },
            )
    }

    private fun handleRun(message: Message) {
        val replyTo = message.replyTo
        val data = message.data
        val dbPath = requireNotNull(data.getString(EXTRA_DB_PATH))
        val writerId = requireNotNull(data.getString(EXTRA_WRITER_ID))
        Thread {
            try {
                val keyManager = SqlCipherKeyManager(applicationContext, AndroidKeystoreKeyProvider())
                keyManager.withWritableDatabase(File(dbPath)) { database ->
                    database.execSQL("CREATE TABLE IF NOT EXISTS race_marker (writer TEXT)")
                    database.execSQL("INSERT INTO race_marker (writer) VALUES ('$writerId')")
                }
                replyTo.send(Message.obtain(null, MSG_DONE))
            } catch (t: Throwable) {
                val reply = Message.obtain(null, MSG_ERROR)
                reply.data = Bundle().apply { putString("error", "${t::class.simpleName}: ${t.message}") }
                replyTo.send(reply)
            }
        }.start()
    }

    override fun onBind(intent: Intent): IBinder = incomingMessenger.binder
}

/** Runs in the `:racetest1` process (see app/src/debug/AndroidManifest.xml). */
class KeyRaceTestServiceProcess1 : KeyRaceTestService()

/** Runs in the `:racetest2` process (see app/src/debug/AndroidManifest.xml). */
class KeyRaceTestServiceProcess2 : KeyRaceTestService()
