package app.readylytics.health.di

import app.readylytics.health.data.preferences.CardConfigurationRepositoryImpl
import app.readylytics.health.data.preferences.DataStoreCircadianThresholdPreferences
import app.readylytics.health.data.preferences.HealthChangeTokenStoreImpl
import app.readylytics.health.data.preferences.ResyncCheckpointStoreImpl
import app.readylytics.health.data.preferences.SleepScoreRecalcBaselineStoreImpl
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.SleepLayoutRepositoryImpl
import app.readylytics.health.data.preferences.VitalsLayoutRepositoryImpl
import app.readylytics.health.data.preferences.WorkoutDetailLayoutRepositoryImpl
import app.readylytics.health.data.preferences.WorkoutsLayoutRepositoryImpl
import app.readylytics.health.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.domain.preferences.CircadianThresholdPreferences
import app.readylytics.health.domain.preferences.SleepScoreRecalcBaselineStore
import app.readylytics.health.domain.sleep.SleepLayoutRepository
import app.readylytics.health.domain.sync.HealthChangeTokenStore
import app.readylytics.health.domain.sync.ResyncCheckpointStore
import app.readylytics.health.domain.vitals.VitalsLayoutRepository
import app.readylytics.health.domain.workouts.WorkoutDetailLayoutRepository
import app.readylytics.health.domain.workouts.WorkoutsLayoutRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindHealthChangeTokenStore(impl: HealthChangeTokenStoreImpl): HealthChangeTokenStore

    @Binds
    @Singleton
    abstract fun bindResyncCheckpointStore(impl: ResyncCheckpointStoreImpl): ResyncCheckpointStore

    @Binds
    @Singleton
    abstract fun bindSleepScoreRecalcBaselineStore(
        impl: SleepScoreRecalcBaselineStoreImpl,
    ): SleepScoreRecalcBaselineStore

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(
        impl: SettingsRepository,
    ): app.readylytics.health.domain.preferences.SettingsRepository

    @Binds
    @Singleton
    abstract fun bindCircadianThresholdPreferences(
        impl: DataStoreCircadianThresholdPreferences,
    ): CircadianThresholdPreferences

    @Binds
    @Singleton
    abstract fun bindCardConfigurationRepository(impl: CardConfigurationRepositoryImpl): CardConfigurationRepository

    @Binds
    @Singleton
    abstract fun bindVitalsLayoutRepository(impl: VitalsLayoutRepositoryImpl): VitalsLayoutRepository

    @Binds
    @Singleton
    abstract fun bindSleepLayoutRepository(impl: SleepLayoutRepositoryImpl): SleepLayoutRepository

    @Binds
    @Singleton
    abstract fun bindWorkoutsLayoutRepository(impl: WorkoutsLayoutRepositoryImpl): WorkoutsLayoutRepository

    @Binds
    @Singleton
    abstract fun bindWorkoutDetailLayoutRepository(
        impl: WorkoutDetailLayoutRepositoryImpl,
    ): WorkoutDetailLayoutRepository
}
