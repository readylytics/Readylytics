package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Remediation Phase 4: every dirty-ticket writer resolves "today" in the stored scoring zone.
 * At 2026-09-30T20:00Z the device (UTC) is still on Sep 30 but Asia/Tokyo is on Oct 1, so an
 * interval correction must invalidate through Oct 1.
 */
@RunWith(RobolectricTestRunner::class)
class RoomHealthChangeIngestionStoreScoringZoneTest {
    private lateinit var database: HealthDatabase
    private lateinit var dirtyRangeStore: RoomDirtyRangeStore
    private lateinit var changeStore: RoomHealthChangeIngestionStore

    @Before
    fun setup() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        runBlocking { database.healthMutationStateDao().getOrCreate() }
        val daos =
            HealthRecordDaos(
                database.sleepSessionDao(),
                database.sleepStageDao(),
                database.heartRateDao(),
                database.hrvDao(),
                database.workoutDao(),
                database.workoutRoutePointDao(),
                database.weightRecordDao(),
                database.bodyFatRecordDao(),
                database.bloodPressureRecordDao(),
                database.oxygenSaturationRecordDao(),
                database.bodyTemperatureRecordDao(),
                database.stepRecordDao(),
                database.sourceRecordDao(),
                database.minuteBucketMaintenanceDao(),
            )
        val settingsRepo =
            mockk<SettingsRepository> {
                every { userPreferences } returns flowOf(UserPreferences(scoringZoneId = "Asia/Tokyo"))
            }
        dirtyRangeStore = RoomDirtyRangeStore(database.dirtyRangeDao(), database.healthMutationStateDao())
        changeStore =
            RoomHealthChangeIngestionStore(
                daos = daos,
                dirtyRangeStore = dirtyRangeStore,
                healthMutationStateDao = database.healthMutationStateDao(),
                settingsRepo = settingsRepo,
                clock = Clock.fixed(Instant.parse("2026-09-30T20:00:00Z"), ZoneOffset.UTC),
                transactionRunner = RoomTransactionRunner(database),
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `interval enrichment ticket closes at the scoring-zone today`() =
        runBlocking {
            changeStore.persistIntervalEnrichment(
                preparedWorkouts = emptyList(),
                sourceUpserts = emptyList(),
                sourceDeletes = emptyList(),
                dirtyDates = setOf(LocalDate.parse("2026-09-25")),
            )

            val ticket = dirtyRangeStore.pending(100).single()
            assertEquals(LocalDate.parse("2026-09-25"), ticket.nextDay)
            assertEquals(LocalDate.parse("2026-10-01"), ticket.endInclusive)
        }
}
