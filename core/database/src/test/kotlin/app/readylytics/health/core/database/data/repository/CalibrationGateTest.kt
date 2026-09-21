package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfigFactory
import app.readylytics.health.core.scoring.domain.scoring.components.Phase
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDayPolicy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task C2 fix-round coverage: [CalibrationGate.isCalibrated]'s frozen short-circuit must consult
 * [app.readylytics.health.core.scoring.domain.scoring.resolveCalibrationState] before trusting a
 * frozen day, so a genuinely inconsistent frozen row (persisted `baselineObservationCount`/
 * `snapshotCalibrationPhase` that disagree) is routed the same way
 * `ComputeSleepMetricsUseCase` would treat it -- falling through to the live eligible-day scan --
 * instead of being blindly trusted from the freeze timestamp alone.
 */
class CalibrationGateTest {
    private val zone = ZoneId.of("UTC")
    private val date = LocalDate.of(2026, 9, 14)
    private val prefs = UserPreferences(scoringZoneId = zone.id)
    private val scoringHistoryRepository = mockk<ScoringHistoryRepository>()
    private val gate = CalibrationGate(scoringHistoryRepository)

    private fun context(summary: DailySummary?): ScoringDayContext =
        ScoringDayContext(
            targetDate = date,
            zoneId = zone,
            dayMidnightMs = date.atStartOfDay(zone).toInstant().toEpochMilli(),
            nextDayMidnightMs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            sleepDayPolicy =
                SleepDayPolicy(
                    coreMergeGapMinutes = prefs.coreMergeGapMinutes,
                    supplementalCutoffMinutesOfDay = prefs.supplementalCutoffMinutesOfDay,
                    minimumCountedSleepSegmentMinutes = prefs.minimumCountedSleepSegmentMinutes,
                    supplementalArchitectureCoveragePercent = prefs.supplementalArchitectureCoveragePercent,
                    scoringZoneId = zone,
                ),
            dailySummary = summary,
            initialBaselines =
                ResolveDailyBaselinesUseCase.InitialBaselines(
                    hrMax = 190f,
                    frozenHrMax = null,
                    frozenRasScalingFactor = null,
                    rhrBaselineValue = 55f,
                    frozenSnapshot = null,
                ),
            scoringConfig =
                ScoringConfigFactory().build(
                    userPreferences = prefs,
                    installDate = date.minusDays(400),
                    currentDate = date,
                ),
            prefs = prefs,
        )

    private fun frozenSummary(
        observationCount: Int?,
        phase: Phase?,
    ) = DailySummary(
        date = date,
        baselineCalculatedAtDate = date.minusDays(1),
        baselineObservationCount = observationCount,
        snapshotCalibrationPhase = phase?.name,
    )

    @Test
    fun `frozen day with agreeing count and phase short-circuits to calibrated without a live scan`() =
        runTest {
            // 60 eligible days resolves to Phase.MATURE via PhaseCalculator -- consistent with the
            // persisted phase, so resolveCalibrationState confirms this row as trusted/mature.
            val summary = frozenSummary(observationCount = 60, phase = Phase.MATURE)

            val result = gate.isCalibrated(context(summary), hasSession = true)

            assertTrue(result, "A consistent frozen-mature row must short-circuit to calibrated")
            coVerify(exactly = 0) { scoringHistoryRepository.countEligibleSleepDaysThrough(any(), any()) }
        }

    @Test
    fun `frozen day with no count or phase metadata trusts the legacy freeze timestamp`() =
        runTest {
            // Pre-C2 legacy rows never persisted these fields; nothing to validate, so the
            // timestamp is trusted exactly as before this fix.
            val summary = frozenSummary(observationCount = null, phase = null)

            val result = gate.isCalibrated(context(summary), hasSession = true)

            assertTrue(result, "A legacy frozen row with no count/phase metadata must still be trusted")
            coVerify(exactly = 0) { scoringHistoryRepository.countEligibleSleepDaysThrough(any(), any()) }
        }

    @Test
    fun `frozen day with mismatched count and phase falls through to the live scan, not calibrated`() =
        runTest {
            // 14 eligible days resolves to Phase.EARLY_BASELINE, not the persisted Phase.MATURE --
            // a genuine mismatch. resolveCalibrationState treats this as needing repair
            // (CalibrationState(null, null, isCalibrating = true)), so the gate must not trust it.
            val summary = frozenSummary(observationCount = 14, phase = Phase.MATURE)
            coEvery {
                scoringHistoryRepository.countEligibleSleepDaysThrough(any(), any())
            } returns 3

            val result = gate.isCalibrated(context(summary), hasSession = false)

            assertFalse(result, "A mismatched frozen row must not be trusted as calibrated")
            coVerify(exactly = 1) { scoringHistoryRepository.countEligibleSleepDaysThrough(any(), any()) }
        }

    @Test
    fun `frozen day with mismatched metadata can still resolve calibrated via the live scan`() =
        runTest {
            // Same mismatch as above, but the live eligible-day scan independently finds enough
            // history to clear the threshold on its own merits -- proving the fallback is a real
            // re-evaluation, not merely "always false".
            val summary = frozenSummary(observationCount = 14, phase = Phase.MATURE)
            coEvery {
                scoringHistoryRepository.countEligibleSleepDaysThrough(any(), any())
            } returns 6

            val result = gate.isCalibrated(context(summary), hasSession = true)

            assertTrue(result, "The live scan must independently resolve calibration once metadata is untrusted")
            coVerify(exactly = 1) { scoringHistoryRepository.countEligibleSleepDaysThrough(any(), any()) }
        }

    @Test
    fun `non-frozen day resolves purely from the live scan`() =
        runTest {
            coEvery {
                scoringHistoryRepository.countEligibleSleepDaysThrough(any(), any())
            } returns 6

            val result = gate.isCalibrated(context(summary = null), hasSession = true)

            assertTrue(result)
            coVerify(exactly = 1) { scoringHistoryRepository.countEligibleSleepDaysThrough(any(), any()) }
        }
}
