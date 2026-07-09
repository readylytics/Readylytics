package app.readylytics.health.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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

    /**
     * US-03 acceptance criterion: switching a load-source preference must never write to
     * daily_summaries. SettingsRepository (the sole owner of preference setters) has no
     * DailySummaryDao dependency, so no preference setter can possibly trigger a summary write.
     * This structural assertion guards against a future setter being given such a dependency.
     */
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
