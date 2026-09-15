package app.readylytics.health.core.database.data.repository.recommendation

import app.readylytics.health.core.database.data.repository.CalibrationGate
import app.readylytics.health.core.database.data.repository.ResidualFatigueComputer
import app.readylytics.health.core.database.data.repository.ScoringDayContext
import app.readylytics.health.core.database.data.repository.ScoringDayDataLoader
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.RecoveryFlag
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.model.SleepSession
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.recommendation.WorkoutRecommendationDecision
import app.readylytics.health.core.model.domain.recommendation.WorkoutRecommendationReason
import app.readylytics.health.core.model.domain.recommendation.WorkoutRecommendationSnapshot
import app.readylytics.health.core.model.domain.recommendation.WorkoutRecommendationState
import app.readylytics.health.core.model.domain.repository.DailySummaryRepository
import app.readylytics.health.core.model.domain.repository.FatigueWorkoutInput
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import app.readylytics.health.core.model.domain.repository.SleepSessionRepository
import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.repository.WorkoutRepository
import app.readylytics.health.core.model.domain.scoring.WorkoutIntensityLevel
import app.readylytics.health.core.model.domain.scoring.WorkoutLoadLevel
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.ComputeResidualFatigueUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfigFactory
import app.readylytics.health.core.scoring.domain.scoring.SleepMetricsRequest
import app.readylytics.health.core.scoring.domain.scoring.WorkoutDisplayMetrics
import app.readylytics.health.core.scoring.domain.scoring.WorkoutLoadClassification
import app.readylytics.health.core.scoring.domain.scoring.sleep.CurrentNightHrvResolver
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDayPolicy
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 24 * HOUR_MS

/**
 * Behavioural coverage for the composed morning snapshot. The sleep-metrics pass and the HRV
 * resolver are stubbed at their boundaries, but their stubs *derive their answers from the request*
 * — a leaked later-in-the-day session or an unbounded evaluation instant changes the stubbed
 * result, and therefore fails the assertions here.
 */
class MorningRecommendationAssemblerTest {
    private val zone = ZoneId.of("UTC")
    private val date = LocalDate.of(2026, 9, 5)
    private val wakeMs = date.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
    private val dayMidnightMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private val nextMidnightMs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    private val prefs =
        UserPreferences(
            scoringZoneId = zone.id,
            residualFatigueHalfLifeHours = 24f,
            residualFatigueGain = 1f,
        )

    private val sleepSessionRepository = mockk<SleepSessionRepository>()
    private val computeSleepMetrics = mockk<ComputeSleepMetricsUseCase>()
    private val hrvResolver = mockk<CurrentNightHrvResolver>()
    private val fatigueDataLoader = mockk<ScoringDayDataLoader>()
    private val workoutRepository = mockk<WorkoutRepository>()
    private val dailySummaryRepository = mockk<DailySummaryRepository>()
    private val displayMetrics = mockk<GetWorkoutDisplayMetricsUseCase>()
    private val baselineComputer = mockk<BaselineComputer>()
    private val scoringHistoryRepository = mockk<ScoringHistoryRepository>()

    private val morningSession =
        sleepData("main", endMs = wakeMs, durationMinutes = 480)
    private val priorNights =
        (1..4).map { sleepData("night-$it", endMs = wakeMs - it * DAY_MS, durationMinutes = 480) }
    private val afternoonNap =
        sleepData("nap", endMs = date.atTime(14, 0).atZone(zone).toInstant().toEpochMilli(), durationMinutes = 200)

    private val seedWorkout = FatigueWorkoutInput("seed", endTimeMs = wakeMs - DAY_MS, trimp = 40f)
    private val eveningWorkout = FatigueWorkoutInput("evening", endTimeMs = wakeMs + 11 * HOUR_MS, trimp = 900f)

    private val runningWorkout =
        workout("run", endMs = wakeMs - 6 * DAY_MS, durationMinutes = 45, exerciseType = "Running")
    private val cyclingWorkout =
        workout("bike", endMs = wakeMs - DAY_MS, durationMinutes = 60, exerciseType = "Cycling")
    private val eveningRow =
        workout("evening", endMs = wakeMs + 12 * HOUR_MS, durationMinutes = 50, exerciseType = "Rowing")

    // region fixtures

    private fun sleepData(
        id: String,
        endMs: Long,
        durationMinutes: Int,
    ) = SleepSessionData(
        id = id,
        deviceName = "watch",
        startTime = endMs - durationMinutes * 60_000L,
        endTime = endMs,
        durationMinutes = durationMinutes,
        efficiency = 0.9f,
        deepSleepMinutes = 90,
        lightSleepMinutes = 280,
        remSleepMinutes = 90,
        awakeMinutes = 20,
    )

    private fun workout(
        id: String,
        endMs: Long,
        durationMinutes: Int,
        exerciseType: String,
        avgHr: Float = 140f,
    ) = WorkoutData(
        id = id,
        startTime = endMs - durationMinutes * 60_000L,
        endTime = endMs,
        exerciseType = exerciseType,
        durationMinutes = durationMinutes,
        zone1Minutes = 0f,
        zone2Minutes = 0f,
        zone3Minutes = 0f,
        zone4Minutes = 0f,
        zone5Minutes = 0f,
        trimp = 60f,
        avgHr = avgHr,
    )

    private fun context(summary: DailySummary? = null): ScoringDayContext =
        ScoringDayContext(
            targetDate = date,
            zoneId = zone,
            dayMidnightMs = dayMidnightMs,
            nextDayMidnightMs = nextMidnightMs,
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

    /**
     * Stubs the sleep-metrics pass so its answer depends on the request bounds: any prefetched
     * session ending after the request's [SleepMetricsRequest.dayEndMs] poisons the result with a
     * strongly negative z-score, which would flip the decision to Rest/Easy.
     */
    private fun stubSleepMetrics(
        zLnHrv: Float? = 0.1f,
        sleepScore: Float? = 80f,
        flags: Set<RecoveryFlag> = emptySet(),
        request: CapturingSlot<SleepMetricsRequest>? = null,
    ) {
        stubBaselines()
        val slot = request ?: slot()
        coEvery { computeSleepMetrics(capture(slot)) } answers {
            val req = slot.captured
            // Models the three ways the real pass can widen past the wake anchor: a leaked
            // session in the prefetch, a day-scoped RHR baseline, or honouring a frozen
            // (next-day-midnight) baseline snapshot instead of the live wake-bounded window.
            val leakedSession = req.prefetchedSessions.orEmpty().any { it.endTime > req.dayEndMs }
            val leakedRhrBaseline = req.rhrBaselineValue > BOUNDED_RHR_BASELINE
            val honoursFreeze =
                !req.forceLiveBaselines && req.summary.baselineCalculatedAtDate != null
            val widened = leakedSession || leakedRhrBaseline || honoursFreeze
            Result.success(
                DailySummary(
                    date = req.targetDate,
                    zLnHrv = if (widened) LEAKED_Z else zLnHrv,
                    sleepScore = if (widened) LEAKED_SLEEP_SCORE else sleepScore,
                    recoveryFlags = flags,
                ),
            )
        }
    }

    /**
     * The wake-bounded RHR baseline the loader is expected to derive, plus enough valid historical
     * nights for the calibration gate to pass. Both answers are computed from the arguments the
     * loader passes, so widening either window changes the value the sleep-metrics stub sees.
     */
    private fun stubBaselines(validHistoricalDayCount: Int = 6) {
        coEvery {
            baselineComputer.computeAdaptiveBaselineRhrBpmBetween(
                fromMs = any(),
                toMs = any(),
                percentile = any(),
                zoneId = any(),
                sleepDayPolicy = any(),
                prefetchedSessions = any(),
                ignoreFrozenSnapshot = any(),
            )
        } answers {
            val upperBoundMs = arg<Long>(1)
            val offered = arg<List<SleepSession>?>(5).orEmpty()
            BOUNDED_RHR_BASELINE + offered.count { it.endTime > upperBoundMs }
        }
        coEvery {
            baselineComputer.computeHrvWindowsBetween(
                fromMs = any(),
                toMs = any(),
                zoneId = any(),
                excludeSessionIds = any(),
                sleepDayPolicy = any(),
                prefetchedSessions = any(),
                ignoreFrozenSnapshot = any(),
            )
        } returns
            BaselineComputer.HrvWindows(
                muHistory = emptyList(),
                sigmaHistory = emptyList(),
                historicalSessions = emptyList(),
                validHistoricalSessionIds = emptyList(),
                validHistoricalDayCount = validHistoricalDayCount,
            )
        // Task C2: CalibrationGate's prior-day count now comes from the cumulative
        // countEligibleSleepDaysThrough port rather than BaselineComputer's HRV window, so the
        // same validHistoricalDayCount value must be mirrored here for these tests to still
        // exercise the calibrating/calibrated boundary they were written for.
        coEvery {
            scoringHistoryRepository.countEligibleSleepDaysThrough(endDay = date.minusDays(1), zoneId = zone)
        } returns validHistoricalDayCount
    }

    private fun stubHrv(mean: Float = 55f) {
        coEvery { hrvResolver.resolve(any(), any()) } returns
            CurrentNightHrvResolver.HrvResult(listOf(mean), mean)
    }

    private fun stubFatigue(inputs: List<FatigueWorkoutInput>, unbackfilled: Int = 0) {
        coEvery { fatigueDataLoader.loadCanonicalFatigueInputsThrough(any()) } answers {
            inputs.filter { it.endTimeMs <= firstArg<Long>() }
        }
        coEvery {
            fatigueDataLoader.loadUnbackfilledCountThrough(retentionStartMs = any(), evaluationTimeMs = any())
        } returns unbackfilled
    }

    private fun stubWorkouts(rows: List<WorkoutData>) {
        coEvery { workoutRepository.getInRange(any(), any()) } answers {
            val from = firstArg<Long>()
            val to = secondArg<Long>()
            rows.filter { it.startTime >= from && it.endTime <= to }
        }
        coEvery { dailySummaryRepository.getInRange(any(), any()) } returns emptyList()
        coEvery { displayMetrics.execute(any(), any(), any(), any()) } answers {
            val w = firstArg<WorkoutData>()
            metricsFor(if (w.id == "run") WorkoutLoadLevel.MODERATE else WorkoutLoadLevel.HARD)
        }
    }

    private fun metricsFor(level: WorkoutLoadLevel) =
        WorkoutDisplayMetrics(
            preciseTrimp = 60f,
            computedTrimp = 60,
            trimpDisplay = "60",
            gainedStrain = 0.1f,
            gainedStrainDisplay = "0.1",
            classification =
                WorkoutLoadClassification(
                    totalTrimp = 60.0,
                    trimpPerMinute = 1.0,
                    baseLoad = level,
                    intensity = WorkoutIntensityLevel.MODERATE,
                    finalLoad = level,
                    wasPromoted = false,
                ),
        )

    private fun assembler(): MorningRecommendationAssembler {
        val fatigueComputer = ResidualFatigueComputer(fatigueDataLoader, ComputeResidualFatigueUseCase())
        return MorningRecommendationAssembler(
            sleepSessionRepository = sleepSessionRepository,
            recoveryLoader =
                MorningRecoveryLoader(
                    sleepSessionRepository = sleepSessionRepository,
                    computeSleepMetricsUseCase = computeSleepMetrics,
                    hrvResolver = hrvResolver,
                    residualFatigueComputer = fatigueComputer,
                    scoringConfigFactory = ScoringConfigFactory(),
                    baselineComputer = baselineComputer,
                    calibrationGate = CalibrationGate(scoringHistoryRepository),
                ),
            exampleLoader =
                WorkoutExampleLoader(
                    workoutRepository = workoutRepository,
                    dailySummaryRepository = dailySummaryRepository,
                    getWorkoutDisplayMetricsUseCase = displayMetrics,
                ),
        )
    }

    // Mirrors the bounded read the assembly now issues: the window ends at the day's end, so a
    // record that runs past it is never even fetched.
    private fun stubSessions(sessions: List<SleepSessionData>) {
        coEvery { sleepSessionRepository.getInRange(any(), any()) } answers {
            val from = firstArg<Long>()
            val to = secondArg<Long>()
            sessions.filter { it.endTime >= from && it.endTime <= to }
        }
    }

    /** Every case that reaches this expects a snapshot; a null assembly is a failure, not a value. */
    private suspend fun MorningRecommendationAssembler.assembleSnapshot(
        context: ScoringDayContext,
        previous: WorkoutRecommendationSnapshot? = null,
    ): WorkoutRecommendationSnapshot = assertNotNull(assemble(context, previous), "expected a snapshot")

    // endregion

    @Test
    fun `an afternoon nap and an evening workout leave the snapshot unchanged`() =
        runTest {
            stubSleepMetrics()
            stubHrv()
            stubWorkouts(listOf(runningWorkout, cyclingWorkout, eveningRow))
            stubFatigue(listOf(seedWorkout))
            stubSessions(priorNights + morningSession)
            val morningOnly = assembler().assembleSnapshot(context())

            stubFatigue(listOf(seedWorkout, eveningWorkout))
            stubSessions(priorNights + morningSession + afternoonNap)
            val endOfDay = assembler().assembleSnapshot(context())

            assertEquals(morningOnly, endOfDay)
            assertEquals(WorkoutRecommendationState.HARDER, morningOnly.decision.state)
            assertEquals("main", morningOnly.wakeSessionId)
            assertEquals(wakeMs, morningOnly.wakeTimeMs)
        }

    @Test
    fun `sleep scoring is asked only for data through the selected wake time`() =
        runTest {
            val captured = slot<SleepMetricsRequest>()
            stubSleepMetrics(request = captured)
            stubHrv()
            stubWorkouts(emptyList())
            stubFatigue(listOf(seedWorkout, eveningWorkout))
            stubSessions(priorNights + morningSession + afternoonNap)

            assembler().assembleSnapshot(context())

            assertEquals(wakeMs, captured.captured.dayEndMs)
            assertEquals(setOf("main"), captured.captured.currentSessionIds)
            assertTrue(captured.captured.prefetchedSessions.orEmpty().all { it.endTime <= wakeMs })
        }

    @Test
    fun `a historical replay reproduces the day-at-a-time snapshot, including on a reused instance`() =
        runTest {
            stubSleepMetrics()
            stubHrv()
            stubWorkouts(listOf(runningWorkout, cyclingWorkout, eveningRow))
            stubFatigue(listOf(seedWorkout))
            stubSessions(priorNights + morningSession)
            val live = assembler().assembleSnapshot(context())

            // Replay months later: the whole day, and everything after it, is now on disk.
            stubFatigue(listOf(seedWorkout, eveningWorkout))
            stubSessions(priorNights + morningSession + afternoonNap)
            val replayed = assembler()
            assertEquals(live, replayed.assembleSnapshot(context()))
            assertEquals(live, replayed.assembleSnapshot(context()))
        }

    @Test
    fun `an unrecorded average heart rate is omitted rather than reported as zero`() =
        runTest {
            stubSleepMetrics()
            stubHrv()
            stubWorkouts(listOf(cyclingWorkout.copy(avgHr = 0f)))
            stubFatigue(listOf(seedWorkout))
            stubSessions(priorNights + morningSession)

            val snapshot = assembler().assembleSnapshot(context())

            assertEquals(listOf("bike"), snapshot.examples.map { it.workoutId })
            assertNull(snapshot.examples.single().averageHr)
        }

    @Test
    fun `examples come from the thirty days before wake, never from later that day`() =
        runTest {
            stubSleepMetrics()
            stubHrv()
            stubWorkouts(listOf(runningWorkout, cyclingWorkout, eveningRow))
            stubFatigue(listOf(seedWorkout))
            stubSessions(priorNights + morningSession)

            val snapshot = assembler().assembleSnapshot(context())

            assertEquals(listOf("bike", "run"), snapshot.examples.map { it.workoutId })
            assertEquals(140f, snapshot.examples.first().averageHr)
        }

    @Test
    fun `replacing the selected record's hrv changes the decision`() =
        runTest {
            stubHrv()
            stubWorkouts(emptyList())
            stubFatigue(listOf(seedWorkout))
            stubSessions(priorNights + morningSession)

            stubSleepMetrics(zLnHrv = 0.1f)
            val healthy = assembler().assembleSnapshot(context())
            stubSleepMetrics(zLnHrv = -2.4f)
            val suppressed = assembler().assembleSnapshot(context())

            assertEquals(WorkoutRecommendationState.HARDER, healthy.decision.state)
            assertEquals(WorkoutRecommendationState.EASY, suppressed.decision.state)
            assertTrue(WorkoutRecommendationReason.HRV_LOW in suppressed.decision.reasons)
            assertTrue(suppressed.examples.isEmpty())
        }

    @Test
    fun `an illness flag rests the day and drops examples`() =
        runTest {
            stubSleepMetrics(zLnHrv = -2.4f, flags = setOf(RecoveryFlag.ILLNESS_ONSET))
            stubHrv()
            stubWorkouts(listOf(runningWorkout, cyclingWorkout))
            stubFatigue(listOf(seedWorkout))
            stubSessions(priorNights + morningSession)

            val snapshot = assembler().assembleSnapshot(context())

            assertEquals(WorkoutRecommendationState.REST, snapshot.decision.state)
            assertTrue(snapshot.examples.isEmpty())
        }

    @Test
    fun `no sleep record on the day reports no sleep without a wake anchor`() =
        runTest {
            stubSleepMetrics()
            stubHrv()
            stubWorkouts(emptyList())
            stubFatigue(listOf(seedWorkout))
            stubSessions(priorNights)

            val snapshot = assembler().assembleSnapshot(context())

            assertEquals(WorkoutRecommendationState.NO_SLEEP, snapshot.decision.state)
            assertNull(snapshot.wakeSessionId)
            assertNull(snapshot.wakeTimeMs)
        }

    @Test
    fun `a missing nightly hrv reading reports no hrv`() =
        runTest {
            stubSleepMetrics()
            stubHrv(mean = 0f)
            stubWorkouts(emptyList())
            stubFatigue(listOf(seedWorkout))
            stubSessions(priorNights + morningSession)

            assertEquals(
                WorkoutRecommendationState.NO_HRV,
                assembler().assembleSnapshot(context()).decision.state,
            )
        }

    @Test
    fun `too few valid historical nights report calibrating`() =
        runTest {
            stubSleepMetrics()
            // 2 prior valid nights + tonight = 3, below the 7-night calibration gate.
            stubBaselines(validHistoricalDayCount = 2)
            stubHrv()
            stubWorkouts(emptyList())
            stubFatigue(listOf(seedWorkout))
            stubSessions(priorNights + morningSession)

            assertEquals(
                WorkoutRecommendationState.CALIBRATING,
                assembler().assembleSnapshot(context()).decision.state,
            )
        }

    @Test
    fun `too little prior history reports no circadian baseline`() =
        runTest {
            stubSleepMetrics()
            stubHrv()
            stubWorkouts(emptyList())
            stubFatigue(listOf(seedWorkout))
            stubSessions(priorNights.take(2) + morningSession)

            val snapshot = assembler().assembleSnapshot(context())

            assertEquals(WorkoutRecommendationState.NO_CIRCADIAN_BASELINE, snapshot.decision.state)
            assertEquals("main", snapshot.wakeSessionId)
        }

    @Test
    fun `prior naps never stand in for a circadian baseline`() =
        runTest {
            stubSleepMetrics()
            stubHrv()
            stubWorkouts(emptyList())
            stubFatigue(listOf(seedWorkout))
            val napHistory =
                (1..4).map { sleepData("nap-$it", endMs = wakeMs - it * DAY_MS, durationMinutes = 120) }
            stubSessions(napHistory + morningSession)

            assertEquals(
                WorkoutRecommendationState.NO_CIRCADIAN_BASELINE,
                assembler().assembleSnapshot(context()).decision.state,
            )
        }

    @Test
    fun `missing optional metrics produce easy rather than an unavailable state`() =
        runTest {
            stubSleepMetrics(sleepScore = null)
            stubHrv()
            stubWorkouts(listOf(runningWorkout))
            stubFatigue(emptyList(), unbackfilled = 1)
            stubSessions(priorNights + morningSession)

            val snapshot = assembler().assembleSnapshot(context())

            assertEquals(WorkoutRecommendationState.EASY, snapshot.decision.state)
            assertEquals(
                listOf(
                    WorkoutRecommendationReason.SLEEP_SCORE_MISSING,
                    WorkoutRecommendationReason.FATIGUE_MISSING,
                ),
                snapshot.decision.reasons,
            )
        }

    @Test
    fun `an unbackfilled canonical trimp row leaves fatigue unknown instead of low`() =
        runTest {
            stubSleepMetrics()
            stubHrv()
            stubWorkouts(emptyList())
            stubFatigue(listOf(seedWorkout), unbackfilled = 1)
            stubSessions(priorNights + morningSession)

            val snapshot = assembler().assembleSnapshot(context())

            assertEquals(WorkoutRecommendationState.EASY, snapshot.decision.state)
            assertEquals(listOf(WorkoutRecommendationReason.FATIGUE_MISSING), snapshot.decision.reasons)
        }

    @Test
    fun `an existing snapshot keeps its source session but re-reads its corrected data`() =
        runTest {
            stubSleepMetrics()
            stubHrv()
            stubWorkouts(emptyList())
            stubFatigue(listOf(seedWorkout))
            val correctedWake = date.atTime(6, 30).atZone(zone).toInstant().toEpochMilli()
            val corrected = morningSession.copy(endTime = correctedWake)
            val closerToHabit = sleepData("second", endMs = wakeMs, durationMinutes = 200)
            stubSessions(priorNights + corrected + closerToHabit)

            val previous =
                WorkoutRecommendationSnapshot(
                    wakeSessionId = "main",
                    wakeTimeMs = wakeMs,
                    decision = WorkoutRecommendationDecision(WorkoutRecommendationState.HARDER),
                )
            val snapshot = assembler().assembleSnapshot(context(), previous)

            assertEquals("main", snapshot.wakeSessionId)
            assertEquals(correctedWake, snapshot.wakeTimeMs)
        }

    @Test
    fun `a deleted source session forces a fresh selection`() =
        runTest {
            stubSleepMetrics()
            stubHrv()
            stubWorkouts(emptyList())
            stubFatigue(listOf(seedWorkout))
            stubSessions(priorNights + morningSession)

            val previous =
                WorkoutRecommendationSnapshot(
                    wakeSessionId = "deleted",
                    wakeTimeMs = wakeMs - HOUR_MS,
                    decision = WorkoutRecommendationDecision(WorkoutRecommendationState.HARDER),
                )
            val snapshot = assembler().assembleSnapshot(context(), previous)

            assertEquals("main", snapshot.wakeSessionId)
            assertEquals(wakeMs, snapshot.wakeTimeMs)
        }

    @Test
    fun `the rhr baseline is re-derived at the wake time, not taken from the day-scoped context`() =
        runTest {
            val captured = slot<SleepMetricsRequest>()
            stubSleepMetrics(request = captured)
            stubHrv()
            stubWorkouts(emptyList())
            stubFatigue(listOf(seedWorkout))
            stubSessions(priorNights + morningSession + afternoonNap)

            assembler().assembleSnapshot(context())

            // 55f is ScoringDayContext.initialBaselines.rhrBaselineValue, computed over the whole
            // day; forwarding it would let the 14:00 nap move zRhr and with it ILLNESS_ONSET.
            assertEquals(BOUNDED_RHR_BASELINE, captured.captured.rhrBaselineValue)
            assertTrue(captured.captured.forceLiveBaselines)
        }

    @Test
    fun `a same-day-later sleep record cannot move the rhr baseline or the recovery flags`() =
        runTest {
            stubSleepMetrics()
            stubHrv()
            stubWorkouts(emptyList())
            stubFatigue(listOf(seedWorkout))

            stubSessions(priorNights + morningSession)
            val morningOnly = assembler().assembleSnapshot(context())

            // A long afternoon sleep with its own HR data: if the RHR baseline window still ran to
            // next-day midnight it would absorb this record.
            stubSessions(priorNights + morningSession + afternoonNap)
            val withLaterRecord = assembler().assembleSnapshot(context())

            assertEquals(morningOnly, withLaterRecord)
            assertEquals(WorkoutRecommendationState.HARDER, morningOnly.decision.state)
            assertTrue(morningOnly.decision.reasons.none { it == WorkoutRecommendationReason.POSSIBLE_ILLNESS })
        }

    @Test
    fun `freezing the day's baseline snapshot does not change the snapshot`() =
        runTest {
            stubSleepMetrics()
            stubHrv()
            stubWorkouts(listOf(runningWorkout, cyclingWorkout))
            stubFatigue(listOf(seedWorkout))
            stubSessions(priorNights + morningSession)

            // First assembly of the day: nothing frozen yet, baselines resolved live.
            val beforeFreeze = assembler().assembleSnapshot(context())

            // Every later assembly: the daily pipeline has since stamped baselineCalculatedAtDate,
            // whose snapshot was bounded at next-day midnight rather than at wake.
            val frozen =
                DailySummary(
                    date = date,
                    baselineCalculatedAtDate = date,
                    hrvMuMssd = 3.9f,
                    hrvSigmaMssd = 0.3f,
                    rhrBpm = 61f,
                    rhrSigma = 2f,
                    snapshotProfile = prefs.physiologyProfile.name,
                )
            val afterFreeze = assembler().assembleSnapshot(context(frozen))

            assertEquals(beforeFreeze, afterFreeze)
            assertEquals(WorkoutRecommendationState.HARDER, beforeFreeze.decision.state)
        }

    @Test
    fun `a fallback source picked without a baseline is not pinned once a baseline appears`() =
        runTest {
            stubSleepMetrics()
            stubHrv()
            stubWorkouts(emptyList())
            stubFatigue(listOf(seedWorkout))
            val earlyEndMs = date.atTime(3, 0).atZone(zone).toInstant().toEpochMilli()
            val earlySegment = sleepData("early", endMs = earlyEndMs, durationMinutes = 200)

            // Day 1: no prior history, so no circadian baseline. Selection degenerates to the
            // earliest end time, which is the 03:00 segment rather than the real morning wake.
            stubSessions(listOf(earlySegment, morningSession))
            val degraded = assembler().assembleSnapshot(context())
            assertEquals(WorkoutRecommendationState.NO_CIRCADIAN_BASELINE, degraded.decision.state)
            assertEquals("early", degraded.wakeSessionId)

            // Backfill later supplies the prior nights, so a baseline now resolves for the same day.
            stubSessions(priorNights + earlySegment + morningSession)
            val recovered = assembler().assembleSnapshot(context(), degraded)

            assertEquals(WorkoutRecommendationState.HARDER, recovered.decision.state)
            assertEquals("main", recovered.wakeSessionId)
            assertEquals(wakeMs, recovered.wakeTimeMs)
        }

    @Test
    fun `an operational read failure propagates instead of reporting no data`() =
        runTest {
            coEvery { sleepSessionRepository.getInRange(any(), any()) } throws IllegalStateException("db closed")

            assertFailsWith<IllegalStateException> { assembler().assemble(context()) }
        }

    @Test
    fun `cancellation propagates`() =
        runTest {
            stubHrv()
            stubWorkouts(emptyList())
            stubFatigue(listOf(seedWorkout))
            stubSessions(priorNights + morningSession)
            stubBaselines()
            coEvery { computeSleepMetrics(any()) } throws CancellationException("cancelled")

            assertFailsWith<CancellationException> { assembler().assemble(context()) }
        }

    @Test
    fun `a failed sleep-metrics pass yields no snapshot instead of aborting the day`() =
        runTest {
            stubHrv()
            stubWorkouts(emptyList())
            stubFatigue(listOf(seedWorkout))
            stubSessions(priorNights + morningSession)
            stubBaselines()
            coEvery { computeSleepMetrics(any()) } returns Result.failure("boom", "SLEEP_METRICS_ERROR")

            // Not an exception: throwing here would abort the whole day's scoring, and during the
            // scoring-version 4->5 backfill one deterministically-failing day would fail every
            // retained-history recompute pass forever.
            assertNull(assembler().assemble(context()))
        }

    private companion object {
        const val LEAKED_Z = -9f
        const val LEAKED_SLEEP_SCORE = 10f
        const val BOUNDED_RHR_BASELINE = 50f
    }
}
