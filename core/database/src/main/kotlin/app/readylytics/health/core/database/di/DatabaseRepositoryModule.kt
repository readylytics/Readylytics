package app.readylytics.health.core.database.di

import app.readylytics.health.core.database.data.local.RoomScanStagingStore
import app.readylytics.health.core.database.data.repository.BloodPressureRepositoryImpl
import app.readylytics.health.core.database.data.repository.BodyFatRepositoryImpl
import app.readylytics.health.core.database.data.repository.DailyMetricsRepositoryImpl
import app.readylytics.health.core.database.data.repository.DailySummaryRepositoryImpl
import app.readylytics.health.core.database.data.repository.HeartRateRepositoryImpl
import app.readylytics.health.core.database.data.repository.SleepSessionRepositoryImpl
import app.readylytics.health.core.database.data.repository.WeightRepositoryImpl
import app.readylytics.health.core.database.data.repository.WorkoutRepositoryImpl
import app.readylytics.health.core.model.domain.repository.BloodPressureRepository
import app.readylytics.health.core.model.domain.repository.BodyFatRepository
import app.readylytics.health.core.model.domain.repository.DailyMetricsRepository
import app.readylytics.health.core.model.domain.repository.DailySummaryRepository
import app.readylytics.health.core.model.domain.repository.HeartRateRepository
import app.readylytics.health.core.model.domain.repository.SleepSessionRepository
import app.readylytics.health.core.model.domain.repository.WeightRepository
import app.readylytics.health.core.model.domain.repository.WorkoutRepository
import app.readylytics.health.core.model.domain.sync.ScanStagingStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DatabaseRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindDailySummaryRepository(impl: DailySummaryRepositoryImpl): DailySummaryRepository

    @Binds
    @Singleton
    abstract fun bindDailyMetricsRepository(impl: DailyMetricsRepositoryImpl): DailyMetricsRepository

    @Binds
    @Singleton
    abstract fun bindWorkoutRepository(impl: WorkoutRepositoryImpl): WorkoutRepository

    @Binds
    @Singleton
    abstract fun bindHeartRateRepository(impl: HeartRateRepositoryImpl): HeartRateRepository

    @Binds
    @Singleton
    abstract fun bindWeightRepository(impl: WeightRepositoryImpl): WeightRepository

    @Binds
    @Singleton
    abstract fun bindBodyFatRepository(impl: BodyFatRepositoryImpl): BodyFatRepository

    @Binds
    @Singleton
    abstract fun bindBloodPressureRepository(impl: BloodPressureRepositoryImpl): BloodPressureRepository

    @Binds
    @Singleton
    abstract fun bindSleepSessionRepository(impl: SleepSessionRepositoryImpl): SleepSessionRepository

    @Binds
    @Singleton
    abstract fun bindScanStagingStore(impl: RoomScanStagingStore): ScanStagingStore
}
