package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.DailyRasValues
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.model.domain.repository.Vo2MaxKey
import app.readylytics.health.core.model.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.model.domain.repository.WalkForwardRasWindow
import app.readylytics.health.core.model.domain.repository.WalkForwardTrimpContext
import app.readylytics.health.core.model.domain.repository.WalkForwardVo2MaxContext
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.model.domain.sync.ScoringRunContext
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.TrimpDateBucketer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap
import javax.inject.Inject
import javax.inject.Singleton

/** Trailing lookback for wearable VO2 Max readings, mirroring [FinalSummaryAssembler]'s per-day window. */
private const val VO2_MAX_LOOKBACK_DAYS = 30L

/**
 * Builds the per-run walk-forward seed contexts (TRIMP, baseline, residual fatigue, RAS, VO2 Max)
 * consumed by the scoring walk-forward recompute. Extracted from `ScoringRepositoryImpl`; the
 * caller is responsible for validating that [ScoringRunContext.zoneId] matches the preferences
 * snapshot before calling [fatigue].
 */
@Singleton
class WalkForwardContextLoader
    @Inject
    constructor(
        private val seriesLoader: ScoringSeriesLoader,
        private val bodyMetricsDataLoader: BodyMetricsDataLoader,
        private val baselineComputer: BaselineComputer,
        private val scoringHistoryRepository: ScoringHistoryRepository,
        private val residualFatigueComputer: ResidualFatigueComputer,
    ) {
        suspend fun trimp(
            startDate: LocalDate,
            endDate: LocalDate,
            zoneId: ZoneId,
        ): WalkForwardTrimpContext {
            val fromMs =
                startDate
                    .minusDays(ScoringConstants.LOAD_LOOKBACK_DAYS)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val toMs =
                endDate
                    .plusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            return WalkForwardTrimpContext(
                dailyTrimpByDate =
                    TreeMap(
                        TrimpDateBucketer.bucket(
                            seriesLoader.loadWorkoutTrimpPoints(fromMs, toMs),
                            zoneId,
                        ),
                    ),
                everydayTrimpByDate =
                    TreeMap(
                        TrimpDateBucketer.bucket(
                            seriesLoader.loadEverydayTrimpPoints(fromMs, toMs),
                            zoneId,
                        ),
                    ),
                trackingStartDate = seriesLoader.loadTrackingStartDate(zoneId),
            )
        }

        suspend fun baseline(
            startDate: LocalDate,
            endDate: LocalDate,
            zoneId: ZoneId,
        ): WalkForwardBaselineContext =
            WalkForwardBaselineContext(
                sessions = baselineComputer.prefetchWalkForwardSessions(startDate, endDate, zoneId),
                scoringHistoryRepository = scoringHistoryRepository,
            )

        suspend fun fatigue(
            startDate: LocalDate,
            prefs: UserPreferences,
            runContext: ScoringRunContext,
        ): WalkForwardFatigueContext =
            residualFatigueComputer.fetchWalkForwardContext(
                startDate = startDate,
                zoneId = runContext.zoneId,
                retentionStartMs = runContext.retentionStartMs,
                prefs = prefs,
            )

        suspend fun ras(
            startDate: LocalDate,
            zoneId: ZoneId,
        ): WalkForwardRasWindow {
            val seedMidnights =
                (1..WalkForwardRasWindow.MAX_WINDOW_DAYS).map { i ->
                    startDate.minusDays(i.toLong()).atStartOfDay(zoneId).toInstant().toEpochMilli()
                }
            val previousSummaries = seriesLoader.loadPreviousDaysSummaries(seedMidnights)
            val seedDays =
                previousSummaries.map { summary ->
                    DailyRasValues(
                        date = Instant.ofEpochMilli(summary.dateMidnightMs).atZone(zoneId).toLocalDate(),
                        rasWorkoutOnly = summary.rasWorkoutOnly,
                        rasEverydayHr = summary.rasEverydayHr,
                    )
                }
            return WalkForwardRasWindow(seedDays)
        }

        suspend fun vo2Max(
            startDate: LocalDate,
            endDate: LocalDate,
            zoneId: ZoneId,
        ): WalkForwardVo2MaxContext {
            val fromMs =
                startDate
                    .minusDays(VO2_MAX_LOOKBACK_DAYS)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val toMs =
                endDate
                    .plusDays(2)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val vo2MaxByTimestampMs = TreeMap<Vo2MaxKey, Float>()
            bodyMetricsDataLoader.loadVo2MaxRange(fromMs, toMs).forEach {
                vo2MaxByTimestampMs[Vo2MaxKey(it.timestampMs, it.id)] = it.vo2Max
            }
            return WalkForwardVo2MaxContext(vo2MaxByTimestampMs)
        }
    }
