package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.model.SleepHrSample
import app.readylytics.health.core.model.domain.model.SleepSession
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.model.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDayAggregator
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDayPolicy
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDaySegment
import app.readylytics.health.core.scoring.domain.util.mean
import app.readylytics.health.core.scoring.domain.util.weightedPercentile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** Per-night values derived for baseline-window math (RHR nadir/percentile, HRV mean, validity). */
internal data class HistoricalSleepDay(
    val scoreDay: LocalDate,
    val coreSessionIds: List<String>,
    val hrvMean: Float?,
    val nadirBpm: Float?,
    val rhrPercentileBpm: Int?,
    val canContributeToBaseline: Boolean,
)

/**
 * UI-002/WP-22: the sleep-session-to-[HistoricalSleepDay] aggregation machinery extracted out of
 * [BaselineComputer], shared by every windowed (`*Between`) and live (`dayMidnight`-anchored)
 * baseline method there. Pure orchestration over [ScoringHistoryRepository] batch reads + the
 * night-validity gate ([ScoringCalculator.validateNight]) -- no baseline-window math itself.
 */
internal class HistoricalSleepDayAssembler(
    private val scoringHistoryRepository: ScoringHistoryRepository,
    private val scoringCalculator: ScoringCalculator,
) {
    suspend fun filterValidBaselineSessions(
        sessions: List<SleepSession>,
        assumeCoverageValid: Boolean = false,
        baselineContext: WalkForwardBaselineContext? = null,
        sourceGen: Long = 0L,
        snapshotId: String = "",
    ): List<String> {
        if (sessions.isEmpty()) return emptyList()

        val hrvMap: Map<String, List<Float>>
        val hrMap: Map<String, Int?>
        if (baselineContext != null) {
            val nightValues = baselineContext.nightValuesForSessions(sessions, sourceGen, snapshotId)
            hrvMap = nightValues.mapValues { it.value.rmssdSamples }
            hrMap = nightValues.mapValues { it.value.averageBpm }
        } else {
            val sessionIds = sessions.map { it.id }
            hrvMap = scoringHistoryRepository.getSleepRmssdForSessionsMap(sessionIds)
            hrMap = scoringHistoryRepository.getAvgSleepHrForSessions(sessionIds)
        }

        return sessions
            .filter { s ->
                val samples = hrvMap[s.id] ?: emptyList()
                val avgHr = hrMap[s.id]

                val validation =
                    if (assumeCoverageValid) {
                        scoringCalculator.validateNight(
                            rmssdMs = if (samples.isNotEmpty()) samples.mean() else null,
                            rhrBpm = avgHr?.toFloat(),
                            durationMinutes = s.durationMinutes,
                            deepMinutes = s.deepSleepMinutes,
                            remMinutes = s.remSleepMinutes,
                            hrCoverageValid = true,
                        )
                    } else {
                        scoringCalculator.validateNight(
                            rmssdMs = if (samples.isNotEmpty()) samples.mean() else null,
                            rhrBpm = avgHr?.toFloat(),
                            durationMinutes = s.durationMinutes,
                            deepMinutes = s.deepSleepMinutes,
                            remMinutes = s.remSleepMinutes,
                        )
                    }

                validation.canContributeToBaseline
            }.map { it.id }
    }

    suspend fun buildHistoricalSleepDays(
        sessions: List<SleepSession>,
        percentile: Int,
        zoneId: ZoneId,
        sleepDayPolicy: SleepDayPolicy? = null,
        assumeCoverageValid: Boolean = false,
        baselineContext: WalkForwardBaselineContext? = null,
        sourceGen: Long = 0L,
        snapshotId: String = "",
    ): List<HistoricalSleepDay> {
        if (sessions.isEmpty()) return emptyList()

        val rmssdBySession: Map<String, List<Float>>
        val hrSamplesBySession: Map<String, List<Int>>
        if (baselineContext != null) {
            val nightValues = baselineContext.nightValuesForSessions(sessions, sourceGen, snapshotId)
            rmssdBySession = nightValues.mapValues { it.value.rmssdSamples }
            hrSamplesBySession = nightValues.mapValues { it.value.orderedBpm }
        } else {
            val sessionIds = sessions.map { it.id }
            rmssdBySession = scoringHistoryRepository.getSleepRmssdForSessionsMap(sessionIds)
            hrSamplesBySession =
                scoringHistoryRepository
                    .getSleepHrProjectionForSessions(sessionIds)
                    .groupBy { it.sessionId }
                    .mapValues { (_, samples) -> samples.map { it.beatsPerMinute } }
        }

        return if (sleepDayPolicy == null) {
            buildDirectFromSessions(
                sessions,
                percentile,
                zoneId,
                assumeCoverageValid,
                rmssdBySession,
                hrSamplesBySession,
            )
        } else {
            buildFromPolicy(
                sessions,
                percentile,
                sleepDayPolicy,
                assumeCoverageValid,
                rmssdBySession,
                hrSamplesBySession,
            )
        }
    }

    private fun buildDirectFromSessions(
        sessions: List<SleepSession>,
        percentile: Int,
        zoneId: ZoneId,
        assumeCoverageValid: Boolean,
        rmssdBySession: Map<String, List<Float>>,
        hrSamplesBySession: Map<String, List<Int>>,
    ): List<HistoricalSleepDay> =
        sessions.map { session ->
            val hrvMean = rmssdBySession[session.id].orEmpty().takeIf { it.isNotEmpty() }?.mean()
            val hrSamples = hrSamplesBySession[session.id].orEmpty().sorted()
            historicalSleepDay(
                scoreDay = Instant.ofEpochMilli(session.endTime).atZone(zoneId).toLocalDate(),
                coreSessionIds = listOf(session.id),
                durationMinutes = session.durationMinutes,
                deepMinutes = session.deepSleepMinutes,
                remMinutes = session.remSleepMinutes,
                hrvMean = hrvMean,
                hrSamples = hrSamples,
                percentile = percentile,
                assumeCoverageValid = assumeCoverageValid,
            )
        }

    private fun buildFromPolicy(
        sessions: List<SleepSession>,
        percentile: Int,
        sleepDayPolicy: SleepDayPolicy,
        assumeCoverageValid: Boolean,
        rmssdBySession: Map<String, List<Float>>,
        hrSamplesBySession: Map<String, List<Int>>,
    ): List<HistoricalSleepDay> =
        SleepDayAggregator
            .aggregate(
                segments = sessions.map(::toSleepDaySegment),
                policy = sleepDayPolicy,
            ).aggregates
            .map { aggregate ->
                val coreSessionIds = aggregate.coreCluster.segments.map { it.stableId }
                val hrvMean =
                    coreSessionIds
                        .flatMap { rmssdBySession[it].orEmpty() }
                        .takeIf { it.isNotEmpty() }
                        ?.mean()
                val hrSamples =
                    coreSessionIds
                        .flatMap { hrSamplesBySession[it].orEmpty() }
                        .sorted()
                historicalSleepDay(
                    scoreDay = aggregate.scoreDay,
                    coreSessionIds = coreSessionIds,
                    durationMinutes = aggregate.totalDurationMinutes,
                    deepMinutes = aggregate.architectureTotals.deepMinutes,
                    remMinutes = aggregate.architectureTotals.remMinutes,
                    hrvMean = hrvMean,
                    hrSamples = hrSamples,
                    percentile = percentile,
                    assumeCoverageValid = assumeCoverageValid,
                )
            }


    private fun historicalSleepDay(
        scoreDay: LocalDate,
        coreSessionIds: List<String>,
        durationMinutes: Int,
        deepMinutes: Int,
        remMinutes: Int,
        hrvMean: Float?,
        hrSamples: List<Int>,
        percentile: Int,
        assumeCoverageValid: Boolean,
    ): HistoricalSleepDay {
        val rhrPercentileBpm = resolvePercentileBpm(hrSamples, percentile)
        val validation =
            if (assumeCoverageValid) {
                scoringCalculator.validateNight(
                    rmssdMs = hrvMean,
                    rhrBpm = rhrPercentileBpm?.toFloat(),
                    durationMinutes = durationMinutes,
                    deepMinutes = deepMinutes,
                    remMinutes = remMinutes,
                    hrCoverageValid = true,
                )
            } else {
                scoringCalculator.validateNight(
                    rmssdMs = hrvMean,
                    rhrBpm = rhrPercentileBpm?.toFloat(),
                    durationMinutes = durationMinutes,
                    deepMinutes = deepMinutes,
                    remMinutes = remMinutes,
                )
            }

        return HistoricalSleepDay(
            scoreDay = scoreDay,
            coreSessionIds = coreSessionIds,
            hrvMean = hrvMean,
            nadirBpm = rhrPercentileBpm?.toFloat()?.takeIf { hrSamples.size >= 10 },
            rhrPercentileBpm = rhrPercentileBpm,
            canContributeToBaseline = validation.canContributeToBaseline,
        )
    }

    private fun resolvePercentileBpm(
        hrSamples: List<Int>,
        percentile: Int,
    ): Int? {
        if (hrSamples.isEmpty()) return null
        val values = hrSamples.toIntArray()
        val weights = IntArray(values.size) { 1 }
        return weightedPercentile(values, weights, percentile / 100.0)
    }

    private fun toSleepDaySegment(session: SleepSession): SleepDaySegment {
        // HC-006: same defensive guard as ScoringRepositoryImpl.toSleepDaySegment -- a
        // stage-less session persisted before the SleepDataMapper raw-span fallback landed can
        // still carry a stored durationMinutes = 0, which SleepDaySegment's `durationMinutes > 0`
        // invariant would otherwise throw on.
        val durationMinutes =
            if (session.durationMinutes > 0) {
                session.durationMinutes
            } else {
                ((session.endTime - session.startTime) / 60_000L).toInt()
            }
        return SleepDaySegment(
            stableId = session.id,
            startTimeMs = session.startTime,
            endTimeMs = session.endTime,
            durationMinutes = durationMinutes,
            lightSleepMinutes = session.lightSleepMinutes,
            deepSleepMinutes = session.deepSleepMinutes,
            remSleepMinutes = session.remSleepMinutes,
            awakeMinutes = session.awakeMinutes,
            efficiency = session.efficiency,
            startZoneOffsetSeconds = session.startZoneOffsetSeconds,
            endZoneOffsetSeconds = session.endZoneOffsetSeconds,
            sourcePackageName = session.deviceName,
        )
    }
}
