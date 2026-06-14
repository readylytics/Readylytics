package app.readylytics.health.di

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.readylytics.health.data.preferences.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.ZoneId
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope {
        val handler =
            kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
                android.util.Log.e("ApplicationScope", "Uncaught exception in app scope", throwable)
            }
        return CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)
    }

    @Provides
    @Singleton
    fun provideCardConfigurationsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<CardConfigurationsProto> =
        DataStoreFactory.create(
            serializer = CardConfigurationsSerializer,
            corruptionHandler =
                ReplaceFileCorruptionHandler {
                    CardConfigurationsSerializer.defaultValue
                },
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            migrations =
                listOf(
                    object : DataMigration<CardConfigurationsProto> {
                        private val oldFile = context.preferencesDataStoreFile("card_configurations")

                        override suspend fun shouldMigrate(currentData: CardConfigurationsProto): Boolean =
                            oldFile.exists()

                        override suspend fun migrate(currentData: CardConfigurationsProto): CardConfigurationsProto {
                            val oldDataStore = PreferenceDataStoreFactory.create(produceFile = { oldFile })
                            val prefs = oldDataStore.data.first()

                            return CardConfigurationsProto
                                .newBuilder()
                                .apply {
                                    prefs[stringPreferencesKey("dashboard_cards")]?.let { json ->
                                        addAllDashboardCards(
                                            LegacyCardConfigurationSerializer
                                                .deserialize(
                                                    json,
                                                ).map { CardConfigurationMapper.toProto(it) },
                                        )
                                    }
                                }.build()
                        }

                        override suspend fun cleanUp() {
                            oldFile.delete()
                        }
                    },
                ),
            produceFile = { context.dataStoreFile("card_configurations.pb") },
        )

    @Provides
    @Singleton
    fun provideUserPreferencesDataStore(
        @ApplicationContext context: Context,
        @ApplicationScope appScope: CoroutineScope,
    ): DataStore<UserPreferencesProto> =
        DataStoreFactory.create(
            serializer = UserPreferencesSerializer,
            corruptionHandler =
                ReplaceFileCorruptionHandler {
                    UserPreferencesProto.getDefaultInstance()
                },
            scope = appScope,
            migrations =
                listOf(
                    object : DataMigration<UserPreferencesProto> {
                        private val oldFile = context.preferencesDataStoreFile("user_preferences")

                        override suspend fun shouldMigrate(currentData: UserPreferencesProto): Boolean =
                            oldFile.exists()

                        override suspend fun migrate(currentData: UserPreferencesProto): UserPreferencesProto {
                            val oldDataStore =
                                androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                                    produceFile = { oldFile },
                                )
                            val prefs = oldDataStore.data.first()

                            return UserPreferencesProto
                                .newBuilder()
                                .apply {
                                    prefs[floatPreferencesKey("goal_sleep_hours")]?.let { goalSleepHours = it }
                                    prefs[
                                        floatPreferencesKey(
                                            "hrv_baseline_override",
                                        ),
                                    ]?.let { hrvBaselineOverride = it }
                                    prefs[
                                        floatPreferencesKey(
                                            "rhr_baseline_override",
                                        ),
                                    ]?.let { rhrBaselineOverride = it }
                                    prefs[stringPreferencesKey("sync_preference")]?.let { value ->
                                        runCatching { SyncPreferenceProto.valueOf("SYNC_${value.uppercase()}") }
                                            .onSuccess { syncPreference = it }
                                    }
                                    prefs[intPreferencesKey("sync_interval_hours")]?.let { syncIntervalHours = it }
                                    prefs[longPreferencesKey("last_sync_timestamp")]?.let { lastSyncTimestamp = it }
                                    prefs[intPreferencesKey("max_heart_rate")]?.let { maxHeartRate = it }
                                    prefs[
                                        booleanPreferencesKey(
                                            "auto_calculate_max_hr",
                                        ),
                                    ]?.let { autoCalculateMaxHr = it }
                                    prefs[booleanPreferencesKey("manual_zone_editing")]?.let { manualZoneEditing = it }
                                    prefs[floatPreferencesKey("zone_1_min_percent")]?.let { zone1MinPercent = it }
                                    prefs[floatPreferencesKey("zone_1_max_percent")]?.let { zone1MaxPercent = it }
                                    prefs[floatPreferencesKey("zone_2_max_percent")]?.let { zone2MaxPercent = it }
                                    prefs[floatPreferencesKey("zone_3_max_percent")]?.let { zone3MaxPercent = it }
                                    prefs[floatPreferencesKey("zone_4_max_percent")]?.let { zone4MaxPercent = it }
                                    prefs[intPreferencesKey("zone_1_min_bpm")]?.let { zone1MinBpm = it }
                                    prefs[intPreferencesKey("zone_1_max_bpm")]?.let { zone1MaxBpm = it }
                                    prefs[intPreferencesKey("zone_2_max_bpm")]?.let { zone2MaxBpm = it }
                                    prefs[intPreferencesKey("zone_3_max_bpm")]?.let { zone3MaxBpm = it }
                                    prefs[intPreferencesKey("zone_4_max_bpm")]?.let { zone4MaxBpm = it }
                                    prefs[intPreferencesKey("age")]?.let { age = it }
                                    prefs[intPreferencesKey("birth_day")]?.let { birthDay = it }
                                    prefs[intPreferencesKey("birth_month")]?.let { birthMonth = it }
                                    prefs[intPreferencesKey("birth_year")]?.let { birthYear = it }
                                    prefs[stringPreferencesKey("gender")]?.let { gender = it }
                                    prefs[
                                        floatPreferencesKey(
                                            "hrv_optimal_threshold",
                                        ),
                                    ]?.let { hrvOptimalThreshold = it }
                                    prefs[
                                        floatPreferencesKey(
                                            "hrv_warning_threshold",
                                        ),
                                    ]?.let { hrvWarningThreshold = it }
                                    prefs[
                                        floatPreferencesKey(
                                            "rhr_optimal_threshold",
                                        ),
                                    ]?.let { rhrOptimalThreshold = it }
                                    prefs[
                                        floatPreferencesKey(
                                            "rhr_warning_threshold",
                                        ),
                                    ]?.let { rhrWarningThreshold = it }
                                    prefs[intPreferencesKey("resting_hr_before_minutes")]?.let {
                                        restingHrBeforeMinutes =
                                            it
                                    }
                                    prefs[intPreferencesKey("resting_hr_after_minutes")]?.let {
                                        restingHrAfterMinutes =
                                            it
                                    }
                                    prefs[stringPreferencesKey("app_theme")]?.let { value ->
                                        runCatching { AppThemeProto.valueOf("THEME_${value.uppercase()}") }
                                            .onSuccess { appTheme = it }
                                    }
                                    prefs[booleanPreferencesKey("dynamic_color_enabled")]?.let {
                                        dynamicColorEnabled =
                                            it
                                    }
                                    prefs[stringPreferencesKey("drive_account_email")]?.let { driveAccountEmail = it }
                                    prefs[stringPreferencesKey("backup_schedule")]?.let { value ->
                                        runCatching { BackupScheduleProto.valueOf("BACKUP_${value.uppercase()}") }
                                            .onSuccess { backupSchedule = it }
                                    }
                                    prefs[longPreferencesKey("last_backup_timestamp")]?.let { lastBackupTimestamp = it }
                                    prefs[intPreferencesKey("consistency_threshold_minutes")]?.let {
                                        consistencyThresholdMinutes =
                                            it
                                    }
                                    prefs[intPreferencesKey("consistency_evaluation_days")]?.let {
                                        consistencyEvaluationDays =
                                            it
                                    }
                                    prefs[intPreferencesKey("consistency_baseline_days")]?.let {
                                        consistencyBaselineDays =
                                            it
                                    }
                                    prefs[floatPreferencesKey("pai_scaling_factor")]?.let { paiScalingFactor = it }
                                    prefs[intPreferencesKey("step_goal")]?.let { stepGoal = it }
                                    prefs[booleanPreferencesKey("retention_days_enabled")]?.let {
                                        retentionDaysEnabled =
                                            it
                                    }
                                    prefs[intPreferencesKey("retention_days")]?.let { retentionDays = it }
                                    prefs[booleanPreferencesKey("collapse_cloud_data")]?.let { collapseCloudData = it }
                                    prefs[booleanPreferencesKey("collapse_health_connect")]?.let {
                                        collapseHealthConnect =
                                            it
                                    }
                                    prefs[booleanPreferencesKey("collapse_baselines_thresholds")]?.let {
                                        collapseBaselinesThresholds =
                                            it
                                    }
                                    prefs[booleanPreferencesKey("collapse_display")]?.let { collapseDisplay = it }
                                    prefs[booleanPreferencesKey("collapse_advanced")]?.let { collapseAdvanced = it }
                                    prefs[booleanPreferencesKey("about_dismissed")]?.let { aboutDismissed = it }
                                    prefs[stringPreferencesKey("physiology_profile")]?.let { value ->
                                        runCatching { PhysiologyProfileProto.valueOf("PROFILE_${value.uppercase()}") }
                                            .onSuccess { physiologyProfile = it }
                                    }
                                    prefs[longPreferencesKey("install_date")]?.let { installDate = it }
                                    prefs[stringPreferencesKey("circadian_threshold_override_encrypted")]?.let {
                                        circadianThresholdOverride =
                                            it
                                    }
                                }.build()
                        }

                        override suspend fun cleanUp() {
                            oldFile.delete()
                        }
                    },
                    // Runs LAST (after the legacy proto rebuild, which would otherwise drop it):
                    // seed the scoring timezone once, capturing the device zone the first time
                    // preferences are read (new installs and existing users alike). After this,
                    // scoring day boundaries are reproducible from stored preferences alone.
                    object : DataMigration<UserPreferencesProto> {
                        override suspend fun shouldMigrate(currentData: UserPreferencesProto): Boolean =
                            currentData.scoringZoneId.isBlank()

                        override suspend fun migrate(currentData: UserPreferencesProto): UserPreferencesProto {
                            val zoneId = ZoneId.systemDefault().id
                            return currentData
                                .toBuilder()
                                .setScoringZoneId(zoneId)
                                .build()
                        }

                        override suspend fun cleanUp() {}
                    },
                    // Canonicalize removed profiles: stored PROFILE_GENERAL / PROFILE_SHIFT_WORKER are
                    // rewritten to PROFILE_ACTIVE so persisted storage matches the supported set.
                    // Reads are already safe via UserPreferences.toDomainProfile; this keeps storage canonical.
                    object : DataMigration<UserPreferencesProto> {
                        override suspend fun shouldMigrate(currentData: UserPreferencesProto): Boolean =
                            currentData.physiologyProfile == PhysiologyProfileProto.PROFILE_GENERAL ||
                                currentData.physiologyProfile == PhysiologyProfileProto.PROFILE_SHIFT_WORKER

                        override suspend fun migrate(currentData: UserPreferencesProto): UserPreferencesProto =
                            currentData
                                .toBuilder()
                                .setPhysiologyProfile(PhysiologyProfileProto.PROFILE_ACTIVE)
                                .build()

                        override suspend fun cleanUp() {}
                    },
                ),
            produceFile = { context.dataStoreFile("user_preferences.pb") },
        )

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemDefaultZone()
}
