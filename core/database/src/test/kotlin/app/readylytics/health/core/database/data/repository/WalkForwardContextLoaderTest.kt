package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.databaseschema.data.local.entity.Vo2MaxRecordEntity
import app.readylytics.health.core.model.domain.model.TimestampedTrimp
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.model.domain.repository.Vo2MaxKey
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.model.domain.repository.WalkForwardRasWindow
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.model.domain.sync.ScoringRunContext
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WalkForwardContextLoaderTest {
    private val seriesLoader = mockk<ScoringSeriesLoader>()
    private val bodyMetricsDataLoader = mockk<BodyMetricsDataLoader>()
    private val baselineComputer = mockk<BaselineComputer>()
    private val scoringHistoryRepository = mockk<ScoringHistoryRepository>()
    private val residualFatigueComputer = mockk<ResidualFatigueComputer>()
    private val loader =
        WalkForwardContextLoader(
            seriesLoader,
            bodyMetricsDataLoader,
            baselineComputer,
            scoringHistoryRepository,
            residualFatigueComputer,
        )

    private fun startOfDayMs(
        date: LocalDate,
        zone: ZoneId,
    ) = date.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `trimp_windowIsLookbackToEndExclusive_inBerlinSpringForward`() =
        runTest {
            val zone = ZoneId.of("Europe/Berlin")
            val startDate = LocalDate.of(2026, 3, 29)
            val endDate = LocalDate.of(2026, 3, 30)
            val expectedFromMs = startOfDayMs(startDate.minusDays(ScoringConstants.LOAD_LOOKBACK_DAYS), zone)
            val expectedToMs = startOfDayMs(endDate.plusDays(1), zone)
            val workoutFrom = slot<Long>()
            val workoutTo = slot<Long>()
            val everydayFrom = slot<Long>()
            val everydayTo = slot<Long>()
            val trackingStart = LocalDate.of(2026, 1, 1)
            coEvery { seriesLoader.loadWorkoutTrimpPoints(capture(workoutFrom), capture(workoutTo)) } returns
                listOf(TimestampedTrimp(expectedFromMs + 1_000L, 10f))
            coEvery { seriesLoader.loadEverydayTrimpPoints(capture(everydayFrom), capture(everydayTo)) } returns
                listOf(TimestampedTrimp(expectedFromMs + 2_000L, 5f))
            coEvery { seriesLoader.loadTrackingStartDate(zone) } returns trackingStart

            val result = loader.trimp(startDate, endDate, zone)

            assertEquals(expectedFromMs, workoutFrom.captured)
            assertEquals(expectedToMs, workoutTo.captured)
            assertEquals(expectedFromMs, everydayFrom.captured)
            assertEquals(expectedToMs, everydayTo.captured)
            assertEquals(trackingStart, result.trackingStartDate)
            assertEquals(1, result.dailyTrimpByDate.size)
            assertEquals(1, result.everydayTrimpByDate.size)
        }

    @Test
    fun `trimp_propagatesCancellation`() =
        runTest {
            val zone = ZoneId.of("UTC")
            coEvery { seriesLoader.loadWorkoutTrimpPoints(any(), any()) } throws CancellationException("cancelled")

            var thrown: Throwable? = null
            try {
                loader.trimp(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2), zone)
            } catch (e: CancellationException) {
                thrown = e
            }

            assertTrue(thrown is CancellationException)
        }

    @Test
    fun `baseline_prefetchesSessionsAndSharesHistoryRepository`() =
        runTest {
            val zone = ZoneId.of("UTC")
            val start = LocalDate.of(2026, 6, 1)
            val end = LocalDate.of(2026, 6, 5)
            coEvery { baselineComputer.prefetchWalkForwardSessions(start, end, zone) } returns emptyList()

            val result = loader.baseline(start, end, zone)

            assertTrue(result.sessions.isEmpty())
            coVerify(exactly = 1) { baselineComputer.prefetchWalkForwardSessions(start, end, zone) }
        }

    @Test
    fun `fatigue_delegatesWithRunContextZoneAndRetentionStart`() =
        runTest {
            val zone = ZoneId.of("Europe/Berlin")
            val start = LocalDate.of(2026, 3, 29)
            val prefs = UserPreferences()
            val runContext = mockk<ScoringRunContext>()
            val expected = WalkForwardFatigueContext(seedInputs = emptyList())
            every { runContext.zoneId } returns zone
            every { runContext.retentionStartMs } returns 123L
            coEvery {
                residualFatigueComputer.fetchWalkForwardContext(
                    startDate = start,
                    zoneId = zone,
                    retentionStartMs = 123L,
                    prefs = prefs,
                )
            } returns expected

            val result = loader.fatigue(start, prefs, runContext)

            assertSame(expected, result)
        }

    @Test
    fun `ras_seedsOneMidnightPerDayUpToMaxWindowDays`() =
        runTest {
            val zone = ZoneId.of("Europe/Berlin")
            val startDate = LocalDate.of(2026, 3, 30)
            val captured = slot<List<Long>>()
            coEvery { seriesLoader.loadPreviousDaysSummaries(capture(captured)) } returns
                listOf(
                    DailySummaryEntity(
                        dateMidnightMs = startOfDayMs(startDate.minusDays(1), zone),
                        rasWorkoutOnly = 3f,
                        rasEverydayHr = 4f,
                    ),
                )

            val result = loader.ras(startDate, zone)

            val expected =
                (1..WalkForwardRasWindow.MAX_WINDOW_DAYS).map { startOfDayMs(startDate.minusDays(it.toLong()), zone) }
            assertEquals(expected, captured.captured)
            assertEquals(1, result.currentEntries().size)
            assertEquals(startDate.minusDays(1), result.currentEntries().single().date)
            assertEquals(3f, result.currentEntries().single().rasWorkoutOnly)
            assertEquals(4f, result.currentEntries().single().rasEverydayHr)
        }

    @Test
    fun `vo2Max_windowIs30DaysBackToEndPlus2`() =
        runTest {
            val zone = ZoneId.of("Europe/Berlin")
            val startDate = LocalDate.of(2026, 3, 29)
            val endDate = LocalDate.of(2026, 3, 30)
            val expectedFromMs = startOfDayMs(startDate.minusDays(30), zone)
            val expectedToMs = startOfDayMs(endDate.plusDays(2), zone)
            val from = slot<Long>()
            val to = slot<Long>()
            coEvery { bodyMetricsDataLoader.loadVo2MaxRange(capture(from), capture(to)) } returns
                listOf(
                    Vo2MaxRecordEntity(
                        id = "a",
                        timestampMs = expectedFromMs + 1L,
                        vo2Max = 44f,
                        measurementMethod = null,
                        deviceName = "dev",
                    ),
                )

            val result = loader.vo2Max(startDate, endDate, zone)

            assertEquals(expectedFromMs, from.captured)
            assertEquals(expectedToMs, to.captured)
            assertEquals(44f, result.vo2MaxByTimestampMs[Vo2MaxKey(expectedFromMs + 1L, "a")])
            assertNull(result.vo2MaxByTimestampMs[Vo2MaxKey(expectedFromMs + 1L, "missing")])
        }
}
