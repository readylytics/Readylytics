package app.readylytics.health.core.database.data.security

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

class DeferredSqlCipherOpenHelper(
    private val configuration: SupportSQLiteOpenHelper.Configuration,
    private val createDelegate: () -> SupportSQLiteOpenHelper,
) : SupportSQLiteOpenHelper {

    private val monitor = Any()
    private var delegate: SupportSQLiteOpenHelper? = null
    private var walEnabled: Boolean? = null

    override val databaseName: String?
        get() = configuration.name

    override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
        synchronized(monitor) {
            val currentDelegate = delegate
            if (currentDelegate != null) {
                currentDelegate.setWriteAheadLoggingEnabled(enabled)
            } else {
                walEnabled = enabled
            }
        }
    }

    override val writableDatabase: SupportSQLiteDatabase
        get() = getOrCreateDelegate().writableDatabase

    override val readableDatabase: SupportSQLiteDatabase
        get() = getOrCreateDelegate().readableDatabase

    override fun close() {
        synchronized(monitor) {
            delegate?.close()
        }
    }

    private fun getOrCreateDelegate(): SupportSQLiteOpenHelper {
        synchronized(monitor) {
            val existing = delegate
            if (existing != null) {
                return existing
            }
            val newDelegate = createDelegate()
            walEnabled?.let { newDelegate.setWriteAheadLoggingEnabled(it) }
            delegate = newDelegate
            return newDelegate
        }
    }
}
