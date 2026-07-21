package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.model.SleepSessionEntity
import app.readylytics.health.domain.repository.ScoringHistoryRepository
import app.readylytics.health.domain.scoring.sleep.SleepDayAggregator
import app.readylytics.health.domain.scoring.sleep.SleepDayPolicy
import app.readylytics.health.domain.scoring.sleep.SleepDaySegment
import app.readylytics.health.domain.util.mean
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
        sessions: List<SleepSessionEntity>,
        assumeCoverageValid: Boolean = false,
    ): List<String> {
        if (sessions.isEmpty()) return emptyList()

        val sessionIds = sessions.map { it.id }
        val hrvMap = scoringHistoryRepository.getSleepRmssdForSessionsMap(sessionIds)
        val hrMap = scoringHistoryRepository.getAvgSleepHrForSessions(sessionIds)

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
        sessions: List<SleepSessionEntity>,
        percentile: Int,
        sleepDayPolicy: SleepDayPolicy?,
        assumeCoverageValid: Boolean = false,
    ): List<HistoricalSleepDay> {
        if (sessions.isEmpty()) return emptyList()

        val sessionIds = sessions.map { it.id }
        val rmssdBySession = scoringHistoryRepository.getSleepRmssdForSessionsMap(sessionIds)
        val hrProjectionBySession =
            scoringHistoryRepository
                .getSleepHrProjectionForSessions(sessionIds)
                .groupBy { it.sessionId }

        return if (sleepDayPolicy == null) {
            sessions.map { session ->
                val hrvMean = rmssdBySession[session.id].orEmpty().takeIf { it.isNotEmpty() }?.mean()
                val hrSamples =
                    hrProjectionBySession[session.id]
                        .orEmpty()
                        .map { it.beatsPerMinute }
                        .sorted()
                historicalSleepDay(
                    scoreDay = Instant.ofEpochMilli(session.endTime).atZone(ZoneId.systemDefault()).toLocalDate(),
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
        } else {
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
                            .flatMap { hrProjectionBySession[it].orEmpty() }
                            .map { it.beatsPerMinute }
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
        }
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
        val index = ((percentile / 100.0) * (hrSamples.size - 1)).roundToInt().coerceIn(0, hrSamples.size - 1)
        return hrSamples[index]
    }

    private fun toSleepDaySegment(session: SleepSessionEntity): SleepDaySegment {
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
