package app.readylytics.health.di

import android.content.Context
import androidx.work.WorkManager
import app.readylytics.health.data.migration.DatabaseReadinessGate
import app.readylytics.health.domain.migration.DatabaseReadinessInspector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WorkerModule {
    @Provides
    @Singleton
    fun provideWorkManager(
        @ApplicationContext context: Context,
    ): WorkManager = WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideWorkerScheduler(
        impl: app.readylytics.health.workers.WorkerSchedulerImpl,
    ): app.readylytics.health.workers.WorkerScheduler = impl

    @Provides
    @Singleton
    fun provideDatabaseMigrationController(
        impl: app.readylytics.health.domain.migration.DatabaseMigrationControllerImpl,
    ): app.readylytics.health.domain.migration.DatabaseMigrationController = impl

    @Provides
    @Singleton
    fun provideDatabaseReadinessInspector(gate: DatabaseReadinessGate): DatabaseReadinessInspector = gate
}
