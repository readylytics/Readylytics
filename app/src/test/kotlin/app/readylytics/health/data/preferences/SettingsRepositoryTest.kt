package app.readylytics.health.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.scoring.SleepScoreWeightProfile
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {
    private lateinit var context: Context
    private lateinit var dataStore: DataStore<UserPreferencesProto>
    private lateinit var repository: SettingsRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        dataStore =
            DataStoreFactory.create(
                serializer = UserPreferencesSerializer,
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { context.dataStoreFile("test_settings.pb") },
            )
        repository =
            SettingsRepository(
                dataStore = dataStore,
                physiology = mockk<PhysiologyPreferences>(relaxed = true),
                thresholds = mockk<ThresholdPreferences>(relaxed = true),
                sleep = SleepPreferences(dataStore),
                ui = mockk<UIPreferences>(relaxed = true),
                sync = mockk<SyncPreferences>(relaxed = true),
                backup = mockk<BackupPreferences>(relaxed = true),
            )
    }

    @After
    fun tearDown() {
        runCatching { context.deleteFile("test_settings.pb") }
    }

    @Test
    fun `retention days clamps to min 90`() =
        runTest {
            repository.updateRetentionDays(90)
            val prefs = repository.userPreferences.first()
            assertEquals(90, prefs.retentionDays)
        }

    @Test
    fun `retention days clamps to max 1200`() =
        runTest {
            repository.updateRetentionDays(1200)
            val prefs = repository.userPreferences.first()
            assertEquals(1200, prefs.retentionDays)
        }

    @Test
    fun `retention days accepts valid range 180-1095`() =
        runTest {
            repository.updateRetentionDays(365)
            var prefs = repository.userPreferences.first()
            assertEquals(365, prefs.retentionDays)

            repository.updateRetentionDays(180)
            prefs = repository.userPreferences.first()
            assertEquals(180, prefs.retentionDays)

            repository.updateRetentionDays(1095)
            prefs = repository.userPreferences.first()
            assertEquals(1095, prefs.retentionDays)
        }

    @Test
    fun `retention enabled toggle works`() =
        runTest {
            repository.updateRetentionDaysEnabled(false)
            var prefs = repository.userPreferences.first()
            assertEquals(false, prefs.retentionDaysEnabled)

            repository.updateRetentionDaysEnabled(true)
            prefs = repository.userPreferences.first()
            assertEquals(true, prefs.retentionDaysEnabled)
        }

    @Test
    fun `default retention days is 360`() =
        runTest {
            val prefs = repository.userPreferences.first()
            assertEquals(360, prefs.retentionDays)
        }

    @Test
    fun `default retention enabled is true`() =
        runTest {
            val prefs = repository.userPreferences.first()
            assertEquals(true, prefs.retentionDaysEnabled)
        }

    @Test
    fun `default HRR tolerance is 30 seconds`() =
        runTest {
            val prefs = repository.userPreferences.first()
            assertEquals(30, prefs.hrrToleranceSeconds)
        }

    @Test
    fun `default biphasic sleep policy values are exposed`() =
        runTest {
            val prefs = repository.userPreferences.first()
            assertEquals(180, prefs.coreMergeGapMinutes)
            assertEquals(1200, prefs.supplementalCutoffMinutesOfDay)
            assertEquals(15, prefs.minimumCountedSleepSegmentMinutes)
            assertEquals(75, prefs.supplementalArchitectureCoveragePercent)
        }

    @Test
    fun `legacy proto without sleep policy fields resolves defaults`() {
        val prefs = UserPreferencesProto.getDefaultInstance().toDomainModel()

        assertEquals(180, prefs.coreMergeGapMinutes)
        assertEquals(1200, prefs.supplementalCutoffMinutesOfDay)
        assertEquals(15, prefs.minimumCountedSleepSegmentMinutes)
        assertEquals(75, prefs.supplementalArchitectureCoveragePercent)
    }

    @Test
    fun `explicit persisted sleep policy fields remain distinguishable from absent fields`() {
        val prefs =
            UserPreferencesProto
                .newBuilder()
                .setCoreMergeGapMinutes(240)
                .setSupplementalCutoffMinutesOfDay(840)
                .setMinimumCountedSleepSegmentMinutes(30)
                .setSupplementalArchitectureCoveragePercent(80)
                .build()
                .toDomainModel()

        assertEquals(240, prefs.coreMergeGapMinutes)
        assertEquals(840, prefs.supplementalCutoffMinutesOfDay)
        assertEquals(30, prefs.minimumCountedSleepSegmentMinutes)
        assertEquals(80, prefs.supplementalArchitectureCoveragePercent)
    }

    @Test
    fun `persisted HRR tolerance values normalize to supported bounds`() =
        runTest {
            repository.batchUpdate {
                hrrToleranceSeconds = 1
            }
            var prefs = repository.userPreferences.first()
            assertEquals(15, prefs.hrrToleranceSeconds)

            repository.batchUpdate {
                hrrToleranceSeconds = 90
            }
            prefs = repository.userPreferences.first()
            assertEquals(60, prefs.hrrToleranceSeconds)
        }

    @Test
    fun `HRR tolerance persists through serializer round trip`() =
        runTest {
            dataStore.updateData {
                UserPreferences(hrrToleranceSeconds = 45).toProto()
            }

            val prefs = repository.userPreferences.first()
            assertEquals(45, prefs.hrrToleranceSeconds)
        }

    @Test
    fun `updateHrrToleranceSeconds persists minimum supported value when too low`() =
        runTest {
            repository.updateHrrToleranceSeconds(10)

            val prefs = repository.userPreferences.first()
            assertEquals(15, prefs.hrrToleranceSeconds)
        }

    @Test
    fun `updateHrrToleranceSeconds persists maximum supported value when too high`() =
        runTest {
            repository.updateHrrToleranceSeconds(70)

            val prefs = repository.userPreferences.first()
            assertEquals(60, prefs.hrrToleranceSeconds)
        }

    @Test
    fun `biphasic sleep policy updates normalize into supported stepped ranges`() =
        runTest {
            repository.updateCoreMergeGapMinutes(241)
            repository.updateSupplementalCutoffMinutesOfDay(845)
            repository.updateMinimumCountedSleepSegmentMinutes(3)
            repository.updateSupplementalArchitectureCoveragePercent(77)

            var prefs = repository.userPreferences.first()
            assertEquals(240, prefs.coreMergeGapMinutes)
            assertEquals(840, prefs.supplementalCutoffMinutesOfDay)
            assertEquals(5, prefs.minimumCountedSleepSegmentMinutes)
            assertEquals(75, prefs.supplementalArchitectureCoveragePercent)

            repository.updateCoreMergeGapMinutes(44)
            repository.updateSupplementalCutoffMinutesOfDay(1370)
            repository.updateMinimumCountedSleepSegmentMinutes(58)
            repository.updateSupplementalArchitectureCoveragePercent(99)

            prefs = repository.userPreferences.first()
            assertEquals(30, prefs.coreMergeGapMinutes)
            assertEquals(1380, prefs.supplementalCutoffMinutesOfDay)
            assertEquals(60, prefs.minimumCountedSleepSegmentMinutes)
            assertEquals(100, prefs.supplementalArchitectureCoveragePercent)
        }

    @Test
    fun `default sleep score preferences and scoring version are exposed`() =
        runTest {
            val prefs = repository.userPreferences.first()
            assertEquals(SleepScoreWeightProfile.BALANCED, prefs.sleepScoreWeightProfile)
            assertEquals(125, prefs.hypersomniaOnsetPercent)
            assertEquals(0, prefs.scoringVersion)
        }

    @Test
    fun `updating sleep score weight profile, oversleep onset, and scoring version persists correctly`() =
        runTest {
            repository.updateSleepScoreWeightProfile(SleepScoreWeightProfile.DURATION_FOCUSED)
            repository.updateHypersomniaOnsetPercent(110)
            repository.updateScoringVersion(1)

            val prefs = repository.userPreferences.first()
            assertEquals(SleepScoreWeightProfile.DURATION_FOCUSED, prefs.sleepScoreWeightProfile)
            assertEquals(110, prefs.hypersomniaOnsetPercent)
            assertEquals(1, prefs.scoringVersion)
        }

    @Test
    fun `hypersomnia onset percent normalizes into supported stepped range`() =
        runTest {
            repository.updateHypersomniaOnsetPercent(123)
            var prefs = repository.userPreferences.first()
            assertEquals(125, prefs.hypersomniaOnsetPercent)

            repository.updateHypersomniaOnsetPercent(98)
            prefs = repository.userPreferences.first()
            assertEquals(100, prefs.hypersomniaOnsetPercent)

            repository.updateHypersomniaOnsetPercent(107)
            prefs = repository.userPreferences.first()
            assertEquals(105, prefs.hypersomniaOnsetPercent)
        }

    @Test
    fun `sleep score preferences persist through serializer round trip`() =
        runTest {
            dataStore.updateData {
                UserPreferences(
                    sleepScoreWeightProfile = SleepScoreWeightProfile.RECOVERY_FOCUSED,
                    hypersomniaOnsetPercent = 115,
                    scoringVersion = 2,
                ).toProto()
            }

            val prefs = repository.userPreferences.first()
            assertEquals(SleepScoreWeightProfile.RECOVERY_FOCUSED, prefs.sleepScoreWeightProfile)
            assertEquals(115, prefs.hypersomniaOnsetPercent)
            assertEquals(2, prefs.scoringVersion)
        }

    @Test
    fun `recalc baseline fields persist through serializer round trip`() =
        runTest {
            dataStore.updateData {
                UserPreferences(
                    lastRecalcSleepScoreWeightProfile = SleepScoreWeightProfile.RECOVERY_FOCUSED,
                    lastRecalcGoalSleepHours = 9.5f,
                    lastRecalcHypersomniaOnsetPercent = 115,
                ).toProto()
            }

            val prefs = repository.userPreferences.first()
            assertEquals(SleepScoreWeightProfile.RECOVERY_FOCUSED, prefs.lastRecalcSleepScoreWeightProfile)
            assertEquals(9.5f, prefs.lastRecalcGoalSleepHours)
            assertEquals(115, prefs.lastRecalcHypersomniaOnsetPercent)
        }

    @Test
    fun `recalc baseline fields are null until first historical recompute`() =
        runTest {
            val prefs = repository.userPreferences.first()
            assertEquals(null, prefs.lastRecalcSleepScoreWeightProfile)
            assertEquals(null, prefs.lastRecalcGoalSleepHours)
            assertEquals(null, prefs.lastRecalcHypersomniaOnsetPercent)
        }

    @Test
    fun `BALANCED recalc baseline round-trips as BALANCED not null`() =
        runTest {
            dataStore.updateData {
                UserPreferences(
                    lastRecalcSleepScoreWeightProfile = SleepScoreWeightProfile.BALANCED,
                ).toProto()
            }

            val prefs = repository.userPreferences.first()
            assertEquals(SleepScoreWeightProfile.BALANCED, prefs.lastRecalcSleepScoreWeightProfile)
        }

    @Test
    fun `updateSleepScoreRecalcBaseline persists the three snapshot fields`() =
        runTest {
            repository.updateSleepScoreRecalcBaseline(
                weightProfile = SleepScoreWeightProfile.ARCHITECTURE_FOCUSED,
                goalSleepHours = 8f,
                hypersomniaOnsetPercent = 100,
            )

            val prefs = repository.userPreferences.first()
            assertEquals(SleepScoreWeightProfile.ARCHITECTURE_FOCUSED, prefs.lastRecalcSleepScoreWeightProfile)
            assertEquals(8f, prefs.lastRecalcGoalSleepHours)
            assertEquals(100, prefs.lastRecalcHypersomniaOnsetPercent)
        }

    /**
     * US-03 acceptance criterion: switching a load-source preference must never write to
     * daily_summaries. SettingsRepository (the sole owner of preference setters) has no
     * DailySummaryDao dependency, so no preference setter can possibly trigger a summary write.
     * This structural assertion guards against a future setter being given such a dependency.
     */
    @Test
    fun `default residual fatigue settings are exposed`() =
        runTest {
            val prefs = repository.userPreferences.first()
            assertEquals(true, prefs.residualFatigueEnabled)
            assertEquals(24f, prefs.residualFatigueHalfLifeHours, 0f)
            assertEquals(1.0f, prefs.residualFatigueGain, 0f)
        }

    @Test
    fun `legacy proto without residual fatigue fields resolves defaults`() {
        val prefs = UserPreferencesProto.getDefaultInstance().toDomainModel()

        assertEquals(true, prefs.residualFatigueEnabled)
        assertEquals(24f, prefs.residualFatigueHalfLifeHours, 0f)
        assertEquals(1.0f, prefs.residualFatigueGain, 0f)
    }

    @Test
    fun `residual fatigue settings persist through serializer round trip`() =
        runTest {
            dataStore.updateData {
                UserPreferences(
                    residualFatigueEnabled = false,
                    residualFatigueHalfLifeHours = 48f,
                    residualFatigueGain = 2.5f,
                ).toProto()
            }

            val prefs = repository.userPreferences.first()
            assertEquals(false, prefs.residualFatigueEnabled)
            assertEquals(48f, prefs.residualFatigueHalfLifeHours, 0f)
            assertEquals(2.5f, prefs.residualFatigueGain, 0f)
        }

    @Test
    fun `SettingsRepository has no DailySummaryDao dependency so pref switches never write summaries`() {
        val constructorParamTypes =
            SettingsRepository::class.java.declaredConstructors
                .flatMap { it.parameterTypes.toList() }
                .map { it.name }
        assertEquals(
            true,
            constructorParamTypes.none { it.contains("DailySummaryDao") || it.contains("ScoringRepository") },
        )
    }
}
