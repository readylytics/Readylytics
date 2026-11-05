package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.model.Contributors
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.ReadinessResult
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.model.SleepSession
import app.readylytics.health.core.model.domain.model.getOrThrow
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.scoring.domain.scoring.components.Phase
import app.readylytics.health.core.scoring.domain.scoring.sleep.CoreRecoveryInput
import app.readylytics.health.core.scoring.domain.scoring.sleep.CurrentNightHrvResolver
import app.readylytics.health.core.scoring.domain.scoring.sleep.HrCoverageValidator
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDayPolicy
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepModifierResolver
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepModifiers
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepNadirAnalyzer
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepPercentileRhrCalculator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Characterization pins for [ComputeSleepMetricsUseCase] (Phase 4 / PR 4.4, Task 15).
 *
 * These pin CURRENT observable behaviour (returned summary, collaborator interactions, failure
 * mapping) so the upcoming extraction of the nocturnal scorer / baseline-window resolver and the
 * decomposition of `invoke` can be proven behaviour-preserving. They deliberately assert on
 * results and `coVerify` counts, never merely "no exception".
 *
 * Overlap with [ComputeSleepMetricsUseCaseTest] (kept as a separate, additive pin set):
 * - `invoke_rethrowsCancellationException` already covers cancellation propagation (via the
 *   history repository); [cancellation_isRethrown_notWrappedInFailure] pins it from the HR
 *   collector, i.e. from inside the try block past the baseline-window step.
 * - `invoke_preservesConsistentFrozenCalibrationState` already verifies
 *   `countEligibleSleepDaysThrough` is not called for a consistent frozen pair; the pin here also
 *   covers the half-present (count-only / phase-only) cases.
 * - The other tests there are builder-only; nothing there covers the no-wake-RHR path, the frozen
 *   baseline-window skip, the live-baseline zone wiring, or the SLEEP_METRICS_ERROR mapping.
 */
class ComputeSleepMetricsUseCaseCharacterizationTest {
    private val targetDate = LocalDate.of(2026, 5, 31)
    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")

    @Test
    fun noWakeRhr_returnsEmptyReadiness_andNullScores() =
        runTest {
            val fixture = Fixture(currentRestingHr = null)
            // Seed non-null scores/readiness so the assertions prove they are RESET, not defaulted.
            val seeded =
                DailySummary(
                    date = targetDate,
                    sleepScore = 80f,
                    readinessWorkoutOnly = 70f,
                    readinessEverydayHr = 60f,
                    sRest = 50f,
                    readinessResult =
                        ReadinessResult.EMPTY.copy(contributors = Contributors(hrvScore = 1f)),
                )

            val result = fixture.useCase(fixture.request(seeded)).getOrThrow()

            assertEquals(ReadinessResult.EMPTY, result.readinessResult)
            assertNull(result.sleepScore)
            assertNull(result.readinessWorkoutOnly)
            assertNull(result.readinessEverydayHr)
            assertNull(result.sRest)
            assertNull(result.zLnHrv)
            assertNull(result.zRhr)
            assertNull(result.restingHeartRate)
            assertTrue(result.recoveryFlags.isEmpty())
        }

    @Test
    fun frozenBaseline_neverCallsRhrHistoryBetweenOrComputeHrvWindowsBetween() =
        runTest {
            val fixture = Fixture()
            val frozen =
                DailySummary(
                    date = targetDate,
                    baselineCalculatedAtDate = targetDate.minusDays(1),
                    hrvMuMssd = 3.9f,
                    hrvSigmaMssd = 0.4f,
                    rhrBpm = 58f,
                    rhrSigma = 3f,
                )

            val result = fixture.useCase(fixture.request(frozen, forceLiveBaselines = false))

            assertTrue(result.isSuccess)
            // Frozen values are carried through untouched (not replaced by live recompute).
            val out = result.getOrThrow()
            assertEquals(targetDate.minusDays(1), out.baselineCalculatedAtDate)
            assertEquals(58f, out.rhrBpm)
            coVerify(exactly = 0) {
                fixture.baselineComputer.rhrHistoryBetween(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(),
                )
            }
            coVerify(exactly = 0) {
                fixture.baselineComputer.computeHrvWindowsBetween(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                )
            }
        }

    @Test
    fun liveBaseline_callsBothBaselineWindowQueriesWithSleepDayPolicyZone() =
        runTest {
            val fixture = Fixture()
            val live = DailySummary(date = targetDate, baselineCalculatedAtDate = null)
            val request = fixture.request(live)

            val result = fixture.useCase(request)

            assertTrue(result.isSuccess)
            coVerify(exactly = 1) {
                fixture.baselineComputer.rhrHistoryBetween(
                    fromMs = request.dayMidnight.toEpochMilli(),
                    toMs = request.dayEndMs,
                    percentile = request.prefs.restingHrPercentile,
                    zoneId = berlin,
                    sleepDayPolicy = match<SleepDayPolicy> { it.scoringZoneId == berlin },
                    prefetchedSessions = any(),
                    baselineContext = any(),
                    sourceGen = any(),
                    snapshotId = any(),
                )
            }
            coVerify(exactly = 1) {
                fixture.baselineComputer.computeHrvWindowsBetween(
                    fromMs = request.dayMidnight.toEpochMilli(),
                    toMs = request.dayEndMs,
                    zoneId = berlin,
                    excludeSessionIds = request.currentSessionIds,
                    sleepDayPolicy = match<SleepDayPolicy> { it.scoringZoneId == berlin },
                    prefetchedSessions = any(),
                    ignoreFrozenSnapshot = false,
                    baselineContext = any(),
                    sourceGen = any(),
                    snapshotId = any(),
                )
            }
        }

    @Test
    fun collaboratorException_returnsFailureWithSleepMetricsErrorCode() =
        runTest {
            val fixture = Fixture()
            coEvery {
                fixture.sleepPercentileRhrCalculator.collect(any(), any(), any(), any())
            } throws RuntimeException("boom")

            val result = fixture.useCase(fixture.request(DailySummary(date = targetDate)))

            val failure = result as? Result.Failure
            assertTrue(failure != null, "expected Result.Failure but was $result")
            assertEquals("SLEEP_METRICS_ERROR", failure.code)
            assertEquals("Failed to compute sleep metrics", failure.reason)
        }

    @Test
    fun cancellation_isRethrown_notWrappedInFailure() =
        runTest {
            val fixture = Fixture()
            coEvery {
                fixture.sleepPercentileRhrCalculator.collect(any(), any(), any(), any())
            } throws CancellationException("cancelled")

            assertFailsWith<CancellationException> {
                fixture.useCase(fixture.request(DailySummary(date = targetDate)))
            }
        }

    @Test
    fun frozenCalibrationMetadata_winsOverLiveEligibleCount() =
        runTest {
            val countOnly =
                DailySummary(date = targetDate, baselineObservationCount = 60, snapshotCalibrationPhase = null)
            val phaseOnly =
                DailySummary(
                    date = targetDate,
                    baselineObservationCount = null,
                    snapshotCalibrationPhase = Phase.MATURE.name,
                )
            val both =
                DailySummary(
                    date = targetDate,
                    baselineObservationCount = 60,
                    snapshotCalibrationPhase = Phase.MATURE.name,
                )

            for (summary in listOf(countOnly, phaseOnly, both)) {
                val fixture = Fixture()
                // A live count that would contradict the frozen metadata if it were consulted.
                coEvery {
                    fixture.scoringHistoryRepository.countEligibleSleepDaysThrough(any(), any())
                } returns 0

                val out = fixture.useCase(fixture.request(summary)).getOrThrow()

                coVerify(exactly = 0) {
                    fixture.scoringHistoryRepository.countEligibleSleepDaysThrough(any(), any())
                }
                // Frozen MATURE/60 resolved: not calibrating, in spite of the live stub of 0.
                assertEquals(false, out.readinessResult.diagnostics.isCalibrating)
            }

            // Counterpart: with NO frozen metadata the live count IS consulted (guards vacuity).
            val live = Fixture()
            coEvery { live.scoringHistoryRepository.countEligibleSleepDaysThrough(any(), any()) } returns 0
            live.useCase(live.request(DailySummary(date = targetDate))).getOrThrow()
            coVerify(exactly = 1) { live.scoringHistoryRepository.countEligibleSleepDaysThrough(any(), any()) }
        }

    /** Wires [ComputeSleepMetricsUseCase] over relaxed/stubbed collaborators with explicit handles. */
    private inner class Fixture(
        currentRestingHr: Int? = 55,
    ) {
        val baselineComputer = mockk<BaselineComputer>(relaxed = true)
        val scoringHistoryRepository = mockk<ScoringHistoryRepository>(relaxed = true)
        val sleepPercentileRhrCalculator = mockk<SleepPercentileRhrCalculator>()
        private val scoringCalculator = mockk<ScoringCalculator>(relaxed = true)

        val useCase: ComputeSleepMetricsUseCase

        init {
            coEvery {
                baselineComputer.rhrHistoryBetween(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(),
                )
            } returns emptyList()
            coEvery {
                baselineComputer.computeHrvWindowsBetween(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                )
            } returns null
            coEvery { scoringHistoryRepository.getDailySummaryByDate(any(), any()) } returns null
            coEvery { scoringCalculator.validateNight(any(), any(), any(), any(), any(), any()) } returns
                ScoringCalculator.NightValidationResult(
                    rmssdValid = true,
                    rhrValid = true,
                    durationValid = true,
                    stagesValid = true,
                    stagesSuspicious = false,
                )
            coEvery { sleepPercentileRhrCalculator.collect(any(), any(), any(), any()) } returns
                SleepPercentileRhrCalculator.SleepPercentileRhrResult(
                    currentRestingHr = currentRestingHr,
                    restingHrBaseline = 58,
                    restingHrRatio = 0.95f,
                )
            val hrvResolver = mockk<CurrentNightHrvResolver>()
            coEvery { hrvResolver.resolve(any(), any()) } returns
                CurrentNightHrvResolver.HrvResult(samples = listOf(40f, 42f), mean = 41f)
            val sleepModifierResolver = mockk<SleepModifierResolver>()
            coEvery { sleepModifierResolver.resolve(any(), any(), any(), any(), any()) } returns
                SleepModifiers(null, null)

            useCase =
                ComputeSleepMetricsUseCase(
                    collaborators =
                        SleepMetricsCollaborators(
                            baselineComputer = baselineComputer,
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

        fun request(
            summary: DailySummary,
            forceLiveBaselines: Boolean = false,
        ): SleepMetricsRequest {
            val session = session()
            val dayMidnight = targetDate.atStartOfDay(berlin).toInstant()
            return SleepMetricsRequest(
                session = session,
                core =
                    CoreRecoveryInput.fromSingleSession(
                        sessionId = session.id,
                        startTimeMs = session.startTime,
                        endTimeMs = session.endTime,
                        coreSleepDurationMinutes = session.durationMinutes,
                        endZoneOffsetSeconds = session.endZoneOffsetSeconds,
                        previousCoreEndZoneOffsetSeconds = null,
                    ),
                dayMidnight = dayMidnight,
                targetDate = targetDate,
                prefs = UserPreferences(scoringZoneId = berlin.id),
                summary = summary,
                loadScore = 50f,
                loadScoreEverydayHr = null,
                zoneId = berlin,
                rhrBaselineValue = 55f,
                dayEndMs = dayMidnight.toEpochMilli() + DAY_MS,
                currentSessionIds = setOf(session.id),
                prefetchedSessions = null,
                forceLiveBaselines = forceLiveBaselines,
                architectureDurationMinutes = session.durationMinutes,
            )
        }
    }

    private fun session() =
        SleepSession(
            id = "characterization-session",
            startTime = 1_000L,
            endTime = 2_000L,
            durationMinutes = 480,
            efficiency = 85f,
            deepSleepMinutes = 90,
            remSleepMinutes = 120,
            lightSleepMinutes = 270,
            awakeMinutes = 20,
        )

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
