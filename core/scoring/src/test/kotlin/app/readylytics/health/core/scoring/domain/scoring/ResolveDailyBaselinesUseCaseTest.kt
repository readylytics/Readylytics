package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase

import app.readylytics.health.core.model.domain.scoring.ScoringConstants

import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDayPolicy
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertFailsWith

class ResolveDailyBaselinesUseCaseTest {
    private val baselineComputer = mockk<BaselineComputer>()
    private lateinit var useCase: ResolveDailyBaselinesUseCase
    private val zoneId = ZoneId.of("UTC")
    private val defaultPrefs = UserPreferences()
    private val policy =
        SleepDayPolicy(
            coreMergeGapMinutes = defaultPrefs.coreMergeGapMinutes,
            supplementalCutoffMinutesOfDay = defaultPrefs.supplementalCutoffMinutesOfDay,
            minimumCountedSleepSegmentMinutes = defaultPrefs.minimumCountedSleepSegmentMinutes,
            supplementalArchitectureCoveragePercent = defaultPrefs.supplementalArchitectureCoveragePercent,
            scoringZoneId = zoneId,
        )

    @Before
    fun setup() {
        useCase = ResolveDailyBaselinesUseCase(baselineComputer)
    }

    @Test
    fun `resolveInitialBaselines uses frozen snapshot if available`() =
        runTest {
            val date = LocalDate.of(2026, 6, 1)
            val frozenSnapshot =
                DailySummary(
                    date = date,
                    baselineCalculatedAtDate = date,
                    hrMax = 185f,
                    rasScalingFactor = 0.22f,
                    rhrBpm = 52f,
                )
            val prefs = UserPreferences(maxHeartRate = 190, rhrBaselineOverride = 55f)

            val result =
                useCase.resolveInitialBaselines(
                    dayMidnightMs = 1000L,
                    nextDayMidnightMs = 2000L,
                    prefs = prefs,
                    dailySummary = frozenSnapshot,
                    sleepDayPolicy = policy,
                    prefetchedSessions = null,
                )

            assertEquals(185f, result.hrMax)
            assertEquals(185f, result.frozenHrMax)
            assertEquals(0.22f, result.frozenRasScalingFactor)
            assertEquals(52f, result.rhrBaselineValue)
            assertEquals(frozenSnapshot, result.frozenSnapshot)
        }

    @Test
    fun `resolveInitialBaselines throws when hrMax is invalid`() =
        runTest {
            val prefs = UserPreferences(autoCalculateMaxHr = false, maxHeartRate = 0, rhrBaselineOverride = 55f)

            assertFailsWith<IllegalStateException> {
                useCase.resolveInitialBaselines(
                    dayMidnightMs = 1000L,
                    nextDayMidnightMs = 2000L,
                    prefs = prefs,
                    dailySummary = null,
                    sleepDayPolicy = policy,
                    prefetchedSessions = null,
                )
            }
        }

    @Test
    fun `resolveInitialBaselines throws when rhrBaselineValue is invalid`() =
        runTest {
            val prefs = UserPreferences(autoCalculateMaxHr = false, maxHeartRate = 190, rhrBaselineOverride = 0f)

            assertFailsWith<IllegalStateException> {
                useCase.resolveInitialBaselines(
                    dayMidnightMs = 1000L,
                    nextDayMidnightMs = 2000L,
                    prefs = prefs,
                    dailySummary = null,
                    sleepDayPolicy = policy,
                    prefetchedSessions = null,
                )
            }
        }

    @Test
    fun `resolveInitialBaselines uses rhrBaselineOverride when not frozen`() =
        runTest {
            val prefs = UserPreferences(autoCalculateMaxHr = false, maxHeartRate = 190, rhrBaselineOverride = 58f)

            val result =
                useCase.resolveInitialBaselines(
                    dayMidnightMs = 1000L,
                    nextDayMidnightMs = 2000L,
                    prefs = prefs,
                    dailySummary = null,
                    sleepDayPolicy = policy,
                    prefetchedSessions = null,
                )

            assertEquals(190f, result.hrMax)
            assertNull(result.frozenHrMax)
            assertNull(result.frozenRasScalingFactor)
            assertEquals(58f, result.rhrBaselineValue)
            assertNull(result.frozenSnapshot)
        }

    @Test
    fun `resolveInitialBaselines computes adaptive baseline when no override or frozen baseline`() =
        runTest {
            val prefs = UserPreferences(maxHeartRate = 190, rhrBaselineOverride = null)
            coEvery {
                baselineComputer.computeAdaptiveBaselineRhrBpmBetween(
                    fromMs = 1000L,
                    toMs = 2000L,
                    percentile = prefs.restingHrPercentile,
                    sleepDayPolicy = policy,
                    prefetchedSessions = null,
                )
            } returns 49f

            val result =
                useCase.resolveInitialBaselines(
                    dayMidnightMs = 1000L,
                    nextDayMidnightMs = 2000L,
                    prefs = prefs,
                    dailySummary = null,
                    sleepDayPolicy = policy,
                    prefetchedSessions = null,
                )

            assertEquals(49f, result.rhrBaselineValue)
        }

    @Test
    fun `resolveInitialBaselines uses default RHR when adaptive baseline returns null`() =
        runTest {
            val prefs = UserPreferences(maxHeartRate = 190, rhrBaselineOverride = null)
            coEvery {
                baselineComputer.computeAdaptiveBaselineRhrBpmBetween(
                    fromMs = 1000L,
                    toMs = 2000L,
                    percentile = prefs.restingHrPercentile,
                    sleepDayPolicy = policy,
                    prefetchedSessions = null,
                )
            } returns null

            val result =
                useCase.resolveInitialBaselines(
                    dayMidnightMs = 1000L,
                    nextDayMidnightMs = 2000L,
                    prefs = prefs,
                    dailySummary = null,
                    sleepDayPolicy = policy,
                    prefetchedSessions = null,
                )

            assertEquals(ScoringConstants.DEFAULT_RHR_BPM, result.rhrBaselineValue)
        }

    @Test
    fun `resolveFinalBaselines preserves frozen values over summary values`() {
        val date = LocalDate.of(2026, 6, 1)
        val frozenSnapshot =
            DailySummary(
                date = date,
                baselineCalculatedAtDate = date,
                hrvMuMssd = 3.8f,
                hrvSigmaMssd = 0.25f,
                rhrBpm = 50f,
                rhrSigma = 1.5f,
            )

        val result =
            useCase.resolveFinalBaselines(
                frozenSnapshot = frozenSnapshot,
                summaryHrvMuMssd = 4.2f,
                summaryHrvSigmaMssd = 0.35f,
                summaryRhrSigma = 2.0f,
                rhrBaselineValue = 54f,
            )

        assertEquals(3.8f, result.hrvMuMssd)
        assertEquals(0.25f, result.hrvSigmaMssd)
        assertEquals(50f, result.rhrBpm)
        assertEquals(1.5f, result.rhrSigma)
    }

    @Test
    fun `resolveFinalBaselines uses summary values when frozenSnapshot is null`() {
        val result =
            useCase.resolveFinalBaselines(
                frozenSnapshot = null,
                summaryHrvMuMssd = 4.2f,
                summaryHrvSigmaMssd = 0.35f,
                summaryRhrSigma = 2.0f,
                rhrBaselineValue = 54f,
            )

        assertEquals(4.2f, result.hrvMuMssd)
        assertEquals(0.35f, result.hrvSigmaMssd)
        assertEquals(54f, result.rhrBpm)
        assertEquals(2.0f, result.rhrSigma)
    }
}
