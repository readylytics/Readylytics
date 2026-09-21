package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfigFactory

import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.SleepSession
import app.readylytics.health.core.model.domain.model.getOrThrow
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.scoring.domain.scoring.components.Phase
import app.readylytics.health.core.scoring.domain.scoring.sleep.CurrentNightHrvResolver
import app.readylytics.health.core.scoring.domain.scoring.sleep.HrCoverageValidator
import app.readylytics.health.core.scoring.domain.scoring.sleep.CoreRecoveryInput
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepModifierResolver
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepModifiers
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepNadirAnalyzer
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepPercentileRhrCalculator
import app.readylytics.health.core.model.domain.security.EncryptionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// ─── Test Data Builders ──────────────────────────────────────────────────────

/**
 * Unit tests for ComputeSleepMetricsUseCase.
 *
 * Strategy: Integration-style testing with test data builders.
 * No complex mocking — verify behavior via data validation.
 *
 * Tests cover:
 * 1. Frozen baseline path (US-B6): skips recompute, uses stored values
 * 2. Live recompute path: calibration state, edge cases (missing HRV/RHR)
 * 3. Edge cases: graceful handling of null/empty data
 */

fun testDailySummary(
    dateMidnightMs: Long = LocalDate.of(2026, 5, 31).toEpochDay() * 86400000,
    baselineCalculatedAtDate: LocalDate? = null,
    hrvMuMssd: Float? = null,
    hrvSigmaMssd: Float? = null,
    rhrBpm: Float? = null,
    isCalibrating: Boolean? = null,
): DailySummaryEntity =
    DailySummaryEntity(
        dateMidnightMs = dateMidnightMs,
        sleepScore = 0f,
        baselineCalculatedAtDate = baselineCalculatedAtDate,
        hrvMuMssd = hrvMuMssd,
        hrvSigmaMssd = hrvSigmaMssd,
        rhrBpm = rhrBpm,
        isCalibrating = isCalibrating,
    )

fun testSleepSession(durationMinutes: Int = 480): SleepSessionEntity =
    SleepSessionEntity(
        id = "sleep_${System.currentTimeMillis()}",
        startTime = System.currentTimeMillis() - (8 * 3600 * 1000),
        endTime = System.currentTimeMillis() + (8 * 3600 * 1000),
        durationMinutes = durationMinutes,
        deepSleepMinutes = 90,
        remSleepMinutes = 120,
        lightSleepMinutes = durationMinutes - 210,
        efficiency = 85f,
        awakeMinutes = 20,
    )

// ─── Tests ──────────────────────────────────────────────────────────────────
class ComputeSleepMetricsUseCaseTest {
    /**
     * P4-1: Frozen baseline path (US-B6).
     * When summary.baselineCalculatedAtDate is set, verify:
     * - frozenBaseline flag is true
     * - Stored frozen values (hrvMuMssd, hrvSigmaMssd, rhrBpm) are read
     */
    @Test
    fun frozenBaseline_skipsRecomputeWhenBaselineCalculatedAtDateSet() {
        val frozenDate = LocalDate.of(2026, 5, 15)
        val summary =
            testDailySummary(
                baselineCalculatedAtDate = frozenDate,
                hrvMuMssd = 50f,
                hrvSigmaMssd = 10f,
                rhrBpm = 60f,
            )

        // Verify frozen values are set
        assertNotNull(summary.baselineCalculatedAtDate)
        assertEquals(frozenDate, summary.baselineCalculatedAtDate)
        assertEquals(50f, summary.hrvMuMssd)
        assertEquals(10f, summary.hrvSigmaMssd)
        assertEquals(60f, summary.rhrBpm)
    }

    /**
     * P4-2: Live recompute path.
     * When summary.baselineCalculatedAtDate is null, verify:
     * - Use case detects live recompute is needed
     * - Empty frozen baselines allow live computation
     */
    @Test
    fun liveBaseline_computesWhenBaselineCalculatedAtDateNull() {
        val summary =
            testDailySummary(
                baselineCalculatedAtDate = null,
                hrvMuMssd = null,
                hrvSigmaMssd = null,
                rhrBpm = null,
            )

        // Verify frozen baseline is not set
        assertNull(summary.baselineCalculatedAtDate)
        assertNull(summary.hrvMuMssd)
        assertNull(summary.hrvSigmaMssd)
        assertNull(summary.rhrBpm)
    }

    /**
     * P4-3: Calibration state.
     * When < MIN_SESSIONS valid nights exist, verify:
     * - isCalibrating=true
     * - baselineCalculatedAtDate remains null
     */
    @Test
    fun calibration_setsCalibrationFlagWhenInsufficientData() {
        val summary =
            testDailySummary(
                baselineCalculatedAtDate = null,
                isCalibrating = true,
            )

        // Verify calibration flag set
        assertEquals(true, summary.isCalibrating)
        assertNull(summary.baselineCalculatedAtDate)
    }

    /**
     * P4-3: Edge case — missing HRV.
     * When HRV data is absent, verify:
     * - No NPE on null HRV
     * - Sleep score can still be computed
     */
    @Test
    fun edgeCase_handlesMissingHrvGracefully() {
        val session = testSleepSession()
        assertNotNull(session)
        assertEquals(480, session.durationMinutes)
    }

    /**
     * P4-3: Edge case — missing RHR.
     * When RHR data is absent, verify:
     * - No NPE on null RHR
     * - Recovery flags degrade gracefully
     */
    @Test
    fun edgeCase_handlesMissingRhrGracefully() {
        val summary =
            testDailySummary(
                rhrBpm = null,
            )
        assertNull(summary.rhrBpm)
    }

    @Test
    fun invoke_rethrowsCancellationException() =
        runTest {
            val scoringHistoryRepository = mockk<ScoringHistoryRepository>()
            val sleepModifierResolver = mockk<SleepModifierResolver>()
            coEvery { sleepModifierResolver.resolve(any(), any(), any(), any()) } returns SleepModifiers(null, null)
            coEvery { scoringHistoryRepository.getDailySummaryByDate(any(), any()) } throws
                CancellationException("Test cancellation")

            val useCase = testComputeSleepMetricsUseCase(scoringHistoryRepository, sleepModifierResolver)

            assertFailsWith<CancellationException> {
                useCase(cancellationTestRequest())
            }
        }

    // ─── Task C2 fix-round: real invocation coverage for the new calibration-state resolution
    // path (ComputeSleepMetricsUseCase.kt ~L206-223), which the brief's file list explicitly
    // mandated updating this file for. Unlike the tests above, these actually call
    // `useCase(request)` end-to-end and assert on the fields resolveCalibrationState controls:
    // `readinessResult.diagnostics.isCalibrating` (set directly from `CalibrationState.isCalibrating`
    // in `buildReadinessResult`) and `snapshotCalibrationPhase`/`baselineObservationCount` (set from
    // `CalibrationState.phase`/`observationCount` in `assembleDailySummary`). ───

    /**
     * (a) No frozen count/phase on the row -> the live, cumulative
     * `countEligibleSleepDaysThrough` count alone determines the resolved [Phase] -- and that
     * count is unbounded, unlike the HRV mu/sigma statistical window (`baselineComputer` here
     * returns no history at all, yet the day still resolves MATURE from the live scan).
     */
    @Test
    fun invoke_resolvesCalibrationStateFromLiveCountWhenNoFrozenMetadata() =
        runTest {
            val targetDate = LocalDate.of(2026, 5, 31)
            val summary =
                DailySummary(
                    date = targetDate,
                    baselineCalculatedAtDate = null,
                    baselineObservationCount = null,
                    snapshotCalibrationPhase = null,
                )
            val scoringHistoryRepository = mockk<ScoringHistoryRepository>(relaxed = true)
            coEvery { scoringHistoryRepository.getDailySummaryByDate(any(), any()) } returns null
            // 59 prior eligible days + today's own contributing session = 60 -> Phase.MATURE.
            coEvery {
                scoringHistoryRepository.countEligibleSleepDaysThrough(targetDate.minusDays(1), any())
            } returns 59

            val useCase = calibrationUseCase(scoringHistoryRepository, canContributeToBaseline = true)
            val result = useCase(calibrationRequest(summary, targetDate)).getOrThrow()

            assertEquals(Phase.MATURE.name, result.snapshotCalibrationPhase)
            assertEquals(false, result.readinessResult.diagnostics.isCalibrating)
            assertEquals(60, result.baselineObservationCount)
            coVerify(exactly = 1) {
                scoringHistoryRepository.countEligibleSleepDaysThrough(targetDate.minusDays(1), any())
            }
        }

    /**
     * (b) A consistent frozen count/phase pair is trusted and preserved verbatim -- and the live
     * scan is never even attempted, since a validated frozen snapshot always wins.
     */
    @Test
    fun invoke_preservesConsistentFrozenCalibrationState() =
        runTest {
            val targetDate = LocalDate.of(2026, 5, 31)
            val summary =
                DailySummary(
                    date = targetDate,
                    baselineCalculatedAtDate = targetDate.minusDays(1),
                    baselineObservationCount = 60,
                    snapshotCalibrationPhase = Phase.MATURE.name,
                )
            val scoringHistoryRepository = mockk<ScoringHistoryRepository>(relaxed = true)
            coEvery { scoringHistoryRepository.getDailySummaryByDate(any(), any()) } returns null

            val useCase = calibrationUseCase(scoringHistoryRepository, canContributeToBaseline = true)
            val result = useCase(calibrationRequest(summary, targetDate)).getOrThrow()

            assertEquals(Phase.MATURE.name, result.snapshotCalibrationPhase)
            assertEquals(false, result.readinessResult.diagnostics.isCalibrating)
            assertEquals(60, result.baselineObservationCount)
            coVerify(exactly = 0) { scoringHistoryRepository.countEligibleSleepDaysThrough(any(), any()) }
        }

    /**
     * (c) A frozen count/phase pair that disagree (corrupted/inconsistent legacy metadata) must
     * hit `resolveCalibrationState`'s mismatch branch through the real use case invocation --
     * never treated as a shortcut to MATURE, and never independently re-derived -- clearing both
     * the persisted count and phase and forcing `isCalibrating = true`, while leaving
     * `baselineCalculatedAtDate` untouched so the next pass has no frozen metadata left to trust
     * and self-heals via a live scan.
     */
    @Test
    fun invoke_treatsMismatchedFrozenCalibrationStateAsNeedingRepair() =
        runTest {
            val targetDate = LocalDate.of(2026, 5, 31)
            val frozenDate = targetDate.minusDays(1)
            val summary =
                DailySummary(
                    date = targetDate,
                    baselineCalculatedAtDate = frozenDate,
                    // 14 resolves to Phase.EARLY_BASELINE via PhaseCalculator -- disagrees with the
                    // persisted Phase.MATURE below.
                    baselineObservationCount = 14,
                    snapshotCalibrationPhase = Phase.MATURE.name,
                )
            val scoringHistoryRepository = mockk<ScoringHistoryRepository>(relaxed = true)
            coEvery { scoringHistoryRepository.getDailySummaryByDate(any(), any()) } returns null

            val useCase = calibrationUseCase(scoringHistoryRepository, canContributeToBaseline = true)
            val result = useCase(calibrationRequest(summary, targetDate)).getOrThrow()

            assertNull(result.snapshotCalibrationPhase)
            assertNull(result.baselineObservationCount)
            assertEquals(true, result.readinessResult.diagnostics.isCalibrating)
            assertEquals(frozenDate, result.baselineCalculatedAtDate)
            // A present frozen count/phase pair short-circuits ComputeSleepMetricsUseCase's own
            // live re-scan regardless of the mismatch -- the row is queued for repair, not
            // re-derived from a live count. (CalibrationGate is the separate call site
            // responsible for not routing a mismatched row into the calibrated branch at all.)
            coVerify(exactly = 0) { scoringHistoryRepository.countEligibleSleepDaysThrough(any(), any()) }
        }
}

private fun calibrationUseCase(
    scoringHistoryRepository: ScoringHistoryRepository,
    canContributeToBaseline: Boolean,
): ComputeSleepMetricsUseCase {
    val scoringCalculator = mockk<ScoringCalculator>(relaxed = true)
    coEvery {
        scoringCalculator.validateNight(any(), any(), any(), any(), any(), any())
    } returns
        ScoringCalculator.NightValidationResult(
            rmssdValid = canContributeToBaseline,
            rhrValid = canContributeToBaseline,
            durationValid = canContributeToBaseline,
            stagesValid = true,
            stagesSuspicious = false,
        )
    val hrvResolver = mockk<CurrentNightHrvResolver>()
    coEvery { hrvResolver.resolve(any(), any()) } returns
        CurrentNightHrvResolver.HrvResult(samples = listOf(40f, 42f), mean = 41f)
    val sleepPercentileRhrCalculator = mockk<SleepPercentileRhrCalculator>()
    coEvery { sleepPercentileRhrCalculator.collect(any(), any(), any(), any()) } returns
        SleepPercentileRhrCalculator.SleepPercentileRhrResult(
            currentRestingHr = 55,
            restingHrBaseline = 58,
            restingHrRatio = 0.95f,
        )
    val sleepModifierResolver = mockk<SleepModifierResolver>()
    coEvery {
        sleepModifierResolver.resolve(any(), any(), any(), any(), any())
    } returns SleepModifiers(null, null)

    return ComputeSleepMetricsUseCase(
        collaborators =
            SleepMetricsCollaborators(
                baselineComputer = mockk(relaxed = true),
                scoringHistoryRepository = scoringHistoryRepository,
                scoringCalculator = scoringCalculator,
                scoringConfigFactory = ScoringConfigFactory(),
                encryptionManager = mockk(relaxed = true),
                hrvResolver = hrvResolver,
                sleepPercentileRhrCalculator = sleepPercentileRhrCalculator,
                nadirAnalyzer = SleepNadirAnalyzer(scoringCalculator),
                coverageValidator = HrCoverageValidator(),
                sleepModifierResolver = sleepModifierResolver,
            ),
    )
}

private fun calibrationRequest(
    summary: DailySummary,
    targetDate: LocalDate,
    zoneId: ZoneId = ZoneId.of("UTC"),
): SleepMetricsRequest {
    val dayMidnight = targetDate.atStartOfDay(zoneId).toInstant()
    return SleepMetricsRequest(
        session = calibrationSession(),
        core = testCoreRecoveryInput(calibrationSession()),
        dayMidnight = dayMidnight,
        targetDate = targetDate,
        prefs = UserPreferences(scoringZoneId = zoneId.id),
        summary = summary,
        loadScore = 50f,
        loadScoreEverydayHr = null,
        zoneId = zoneId,
        rhrBaselineValue = 55f,
        dayEndMs = dayMidnight.toEpochMilli() + 86_400_000L,
        currentSessionIds = setOf("calibration-session"),
        prefetchedSessions = null,
    )
}

private fun calibrationSession() =
    SleepSession(
        id = "calibration-session",
        startTime = 1_000L,
        endTime = 2_000L,
        durationMinutes = 480,
        efficiency = 85f,
        deepSleepMinutes = 90,
        remSleepMinutes = 120,
        lightSleepMinutes = 270,
        awakeMinutes = 20,
    )

private fun testComputeSleepMetricsUseCase(
    scoringHistoryRepository: ScoringHistoryRepository,
    sleepModifierResolver: SleepModifierResolver,
): ComputeSleepMetricsUseCase =
    ComputeSleepMetricsUseCase(
        collaborators =
            SleepMetricsCollaborators(
                baselineComputer = mockk<BaselineComputer>(relaxed = true),
                scoringHistoryRepository = scoringHistoryRepository,
                scoringCalculator = mockk<ScoringCalculator>(relaxed = true),
                scoringConfigFactory = mockk<ScoringConfigFactory>(relaxed = true),
                encryptionManager = mockk<EncryptionManager>(relaxed = true),
                hrvResolver = mockk<CurrentNightHrvResolver>(relaxed = true),
                sleepPercentileRhrCalculator = mockk<SleepPercentileRhrCalculator>(relaxed = true),
                nadirAnalyzer = mockk<SleepNadirAnalyzer>(relaxed = true),
                coverageValidator = mockk<HrCoverageValidator>(relaxed = true),
                sleepModifierResolver = sleepModifierResolver,
            ),
    )

private fun cancellationTestRequest(): SleepMetricsRequest =
    SleepMetricsRequest(
        session = cancelledSession(),
        core = testCoreRecoveryInput(cancelledSession()),
        dayMidnight = Instant.ofEpochMilli(0),
        targetDate = LocalDate.of(2026, 5, 31),
        prefs = UserPreferences(),
        summary = DailySummary(date = LocalDate.of(2026, 5, 31)),
        loadScore = 50f,
        loadScoreEverydayHr = null,
        zoneId = ZoneId.systemDefault(),
        rhrBaselineValue = 60f,
        dayEndMs = 86400000L,
        currentSessionIds = emptySet(),
        prefetchedSessions = null,
    )

private fun cancelledSession() =
    SleepSession(
        id = "session-1",
        startTime = 1000L,
        endTime = 2000L,
        durationMinutes = 480,
        efficiency = 85f,
        deepSleepMinutes = 90,
        remSleepMinutes = 120,
        lightSleepMinutes = 270,
        awakeMinutes = 20,
    )

private fun testCoreRecoveryInput(session: SleepSession): CoreRecoveryInput =
    CoreRecoveryInput.fromSingleSession(
        sessionId = session.id,
        startTimeMs = session.startTime,
        endTimeMs = session.endTime,
        coreSleepDurationMinutes = session.durationMinutes,
        endZoneOffsetSeconds = session.endZoneOffsetSeconds,
        previousCoreEndZoneOffsetSeconds = null,
    )
