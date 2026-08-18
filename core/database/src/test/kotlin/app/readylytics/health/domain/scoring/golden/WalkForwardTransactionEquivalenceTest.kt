package app.readylytics.health.domain.scoring.golden

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.local.RoomTransactionRunner
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.data.repository.ScoringHistoryRepositoryImpl
import app.readylytics.health.data.repository.ScoringRepositoryImpl
import app.readylytics.health.domain.scoring.AssembleDailySummaryUseCase
import app.readylytics.health.domain.scoring.AssembleEverydayLoadInputUseCase
import app.readylytics.health.domain.scoring.BaselineComputer
import app.readylytics.health.domain.scoring.BuildLoadSeriesUseCase
import app.readylytics.health.domain.scoring.CompositeScoringCalculator
import app.readylytics.health.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.domain.scoring.ResolveDailyBaselinesUseCase
import app.readylytics.health.domain.scoring.ScoringConfigFactory
import app.readylytics.health.domain.scoring.sleep.CurrentNightHrvResolver
import app.readylytics.health.domain.scoring.sleep.HrCoverageValidator
import app.readylytics.health.domain.scoring.sleep.SleepNadirAnalyzer
import app.readylytics.health.domain.scoring.sleep.SleepPercentileRhrCalculator
import app.readylytics.health.domain.scoring.strategies.LoadScoringStrategy
import app.readylytics.health.domain.scoring.strategies.RasScoringStrategy
import app.readylytics.health.domain.scoring.strategies.SleepScoringStrategy
import app.readylytics.health.domain.sync.DailyRecomputeSupport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

/**
 * F7 equivalence lock. Wrapping the walk-forward in one Room transaction must not change a single
 * persisted value.
 *
 * This is not a redundant restatement of the mock-level transaction-count tests: it is the only
 * check that exercises the walk-forward's read-after-write dependencies for real. Day N's
 * `totalRas*` sums days N-1..N-6 (`ScoringRepositoryImpl.sumRasLastSixDays`) and
 * `ComputeSleepMetricsUseCase` reads day N-1, so any implementation that defers the writes past
 * the days that read them -- e.g. buffering the summaries and calling `upsertAll` after the loop --
 * silently produces different scores. Reads inside a transaction see that transaction's own
 * uncommitted writes, so the transaction-wrapped run must match the per-day-commit run exactly.
 *
 * `workout_records` is compared too: `ScoringRepositoryImpl` writes `modelTrimp` per recomputed day,
 * and inside one transaction those N writes coalesce into a single invalidation for free -- this
 * asserts the coalescing is invisible in the data.
 *
 * Deliberately NOT built on `GoldenFixtureWalkForwardTest`'s checked-in JSON, which is documented
 * as known-stale (WP-10, WP-11). An A/B comparison of two runs cannot go stale.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class WalkForwardTransactionEquivalenceTest {
    private val zoneId: ZoneId = ZoneId.of("Europe/Berlin")

    // GoldenFixtureDataBuilder requires startDate to be at least 95 days before endDate (it places
    // its scenario days at fixed offsets up to +94). 120 days keeps the run short while still
    // covering the stage-less night, the biphasic night, and the multi-day gap.
    private val startDate: LocalDate = LocalDate.of(2024, 6, 1)
    private val endDate: LocalDate = startDate.plusDays(119)

    @Test
    fun `transaction-wrapped walk-forward produces identical rows to per-day commits`() =
        runBlocking {
            val perDay = runWalkForward(wrapInTransaction = false)
            val batched = runWalkForward(wrapInTransaction = true)

            assertEquals(perDay.summaries, batched.summaries)
            assertEquals(perDay.workouts, batched.workouts)
            // Guard against both runs silently producing nothing.
            assertEquals(120, perDay.summaries.size)
        }

    private data class RunOutput(
        val summaries: List<String>,
        val workouts: List<String>,
    )

    private suspend fun runWalkForward(wrapInTransaction: Boolean): RunOutput {
        val db =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).build()
        try {
            val prefs =
                UserPreferences(
                    scoringZoneId = zoneId.id,
                    installDate =
                        startDate
                            .minusDays(1)
                            .atStartOfDay(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                    age = 35,
                )
            // Default seed => both runs get byte-identical seeded data.
            val buildResult = GoldenFixtureDataBuilder(zoneId).build(db, startDate, endDate)

            val settingsRepo = FakeSettingsRepository(prefs)
            val scoringHistoryRepository =
                ScoringHistoryRepositoryImpl(
                    db.heartRateDao(),
                    db.hrvDao(),
                    db.sleepSessionDao(),
                    db.dailySummaryDao(),
                    db.minuteBucketDao(),
                )
            val loadScoringStrategy = LoadScoringStrategy()
            val scoringCalculator =
                CompositeScoringCalculator(
                    sleepStrategy = SleepScoringStrategy(loadScoringStrategy),
                    rasStrategy = RasScoringStrategy(),
                    loadStrategy = loadScoringStrategy,
                )
            val baselineComputer = BaselineComputer(scoringHistoryRepository, scoringCalculator)
            val scoringConfigFactory = ScoringConfigFactory()
            val scoringRepository =
                ScoringRepositoryImpl(
                    workoutDao = db.workoutDao(),
                    sleepSessionDao = db.sleepSessionDao(),
                    dailySummaryDao = db.dailySummaryDao(),
                    settingsRepo = settingsRepo,
                    baselineComputer = baselineComputer,
                    buildLoadSeriesUseCase = BuildLoadSeriesUseCase(scoringCalculator),
                    assembleEverydayLoadInputUseCase = AssembleEverydayLoadInputUseCase(),
                    computeSleepMetricsUseCase =
                        ComputeSleepMetricsUseCase(
                            baselineComputer = baselineComputer,
                            scoringHistoryRepository = scoringHistoryRepository,
                            scoringCalculator = scoringCalculator,
                            scoringConfigFactory = scoringConfigFactory,
                            encryptionManager = FakeEncryptionManager(),
                            hrvResolver = CurrentNightHrvResolver(scoringHistoryRepository),
                            sleepPercentileRhrCalculator =
                                SleepPercentileRhrCalculator(scoringHistoryRepository),
                            nadirAnalyzer = SleepNadirAnalyzer(scoringCalculator),
                            coverageValidator = HrCoverageValidator(),
                        ),
                    scoringConfigFactory = scoringConfigFactory,
                    computeDailyTrimpUseCase = ComputeDailyTrimpUseCase(ComputeWorkoutTrimpUseCase()),
                    resolveDailyBaselinesUseCase = ResolveDailyBaselinesUseCase(baselineComputer),
                    assembleDailySummaryUseCase = AssembleDailySummaryUseCase(),
                    heartRateDao = db.heartRateDao(),
                    minuteBucketDao = db.minuteBucketDao(),
                    weightRecordDao = db.weightRecordDao(),
                    bodyFatRecordDao = db.bodyFatRecordDao(),
                    bloodPressureRecordDao = db.bloodPressureRecordDao(),
                    oxygenSaturationRecordDao = db.oxygenSaturationRecordDao(),
                    bodyTemperatureRecordDao = db.bodyTemperatureRecordDao(),
                    scoringHistoryRepository = scoringHistoryRepository,
                    defaultDispatcher = UnconfinedTestDispatcher(),
                )
            val recomputeSupport =
                DailyRecomputeSupport(scoringRepository, settingsRepo, RoomTransactionRunner(db))

            val walkForward: suspend () -> Unit = {
                val trimpContext =
                    recomputeSupport.buildWalkForwardTrimpContext(startDate, endDate, zoneId)
                val baselineContext =
                    recomputeSupport.buildWalkForwardBaselineContext(startDate, endDate, zoneId)
                var day = startDate
                while (!day.isAfter(endDate)) {
                    recomputeSupport.recomputeDay(
                        day,
                        buildResult.stepsByDate[day],
                        prefs,
                        trimpContext,
                        baselineContext,
                    )
                    day = day.plusDays(1)
                }
            }

            if (wrapInTransaction) {
                recomputeSupport.inRecomputeTransaction { walkForward() }
            } else {
                walkForward()
            }

            return RunOutput(
                summaries =
                    db
                        .dailySummaryDao()
                        .getAllSummaries()
                        .sortedBy { it.dateMidnightMs }
                        .map { it.toString() },
                workouts =
                    db
                        .workoutDao()
                        .getWorkoutsInRange(
                            startDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                            endDate
                                .plusDays(1)
                                .atStartOfDay(zoneId)
                                .toInstant()
                                .toEpochMilli(),
                        ).sortedBy { it.startTime }
                        .map { it.toString() },
            )
        } finally {
            db.close()
        }
    }
}
