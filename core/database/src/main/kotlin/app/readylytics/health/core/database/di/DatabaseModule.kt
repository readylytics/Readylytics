package app.readylytics.health.core.database.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.readylytics.health.core.database.data.local.DatabaseMigrations
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.local.RoomTransactionRunner
import app.readylytics.health.core.database.data.local.RoomWalDiagnostics
import app.readylytics.health.core.database.data.migration.DatabaseReadinessGate
import app.readylytics.health.core.database.data.security.AndroidKeystoreKeyProvider
import app.readylytics.health.core.database.data.security.KeyProvider
import app.readylytics.health.core.database.data.security.SqlCipherKeyManager
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.repository.WalDiagnostics
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DatabaseModule {
    @Binds
    abstract fun bindTransactionRunner(impl: RoomTransactionRunner): TransactionRunner

    @Binds
    abstract fun bindWalDiagnostics(impl: RoomWalDiagnostics): WalDiagnostics

    @Binds
    abstract fun bindKeyProvider(impl: AndroidKeystoreKeyProvider): KeyProvider

    companion object {
        @Provides
        @Singleton
        @Suppress("SpreadOperator")
        fun provideDatabase(
            @ApplicationContext context: Context,
            sqlCipherKeyManager: SqlCipherKeyManager,
            databaseReadinessGate: DatabaseReadinessGate,
        ): HealthDatabase {
            val dbFile = context.getDatabasePath("health_dashboard.db")
            sqlCipherKeyManager.migrateIfNeeded(dbFile)
            requireDatabaseReady(databaseReadinessGate)

            val builder =
                Room
                    .databaseBuilder<HealthDatabase>(context, "health_dashboard.db")
                    .openHelperFactory(sqlCipherKeyManager.getOrCreateFactory())
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .setQueryCoroutineContext(Dispatchers.IO)
                    .addMigrations(*DatabaseMigrations.all)
                    .addCallback(
                        object : RoomDatabase.Callback() {
                            override fun onOpen(db: SupportSQLiteDatabase) {
                                super.onOpen(db)
                                db.execSQL("PRAGMA synchronous = NORMAL")
                                db.execSQL("PRAGMA foreign_keys = ON")
                            }
                        },
                    )

            return builder.build()
        }
    }
}

fun requireDatabaseReady(databaseReadinessGate: DatabaseReadinessGate) {
    check(databaseReadinessGate.inspect() == DatabaseReadiness.Ready) {
        "HealthDatabase cannot open before the external v7 migration is complete"
    }
}
