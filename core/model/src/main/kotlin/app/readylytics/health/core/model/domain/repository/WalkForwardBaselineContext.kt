package app.readylytics.health.core.model.domain.repository

import app.readylytics.health.core.model.domain.model.SleepSession

/**
 * Key for run-local derived sleep night values, bound to the immutable run's
 * source generation and scoring snapshot.
 */
data class NightCacheKey(
    val sessionId: String,
    val sourceGeneration: Long,
    val scoringSnapshotId: String,
)

/**
 * Derived per-night metrics used by sleep baseline calculations (RHR percentile nadir,
 * average RHR, and RMSSD samples).
 */
data class NightValues(
    val orderedBpm: List<Int>,
    val averageBpm: Int?,
    val rmssdSamples: List<Float> = emptyList(),
) {
    fun getPercentileValue(percentile: Int): Int? {
        if (orderedBpm.isEmpty()) return null
        val index =
            Math.round((percentile / 100.0) * (orderedBpm.size - 1)).toInt().coerceIn(0, orderedBpm.size - 1)
        return orderedBpm[index]
    }
}

/**
 * PERF-002/WP-22: sleep sessions covering the widest RHR/HRV baseline lookback (56 days), fetched
 * once for the duration of one walk-forward (daily sync or resync recompute) and shared across
 * every day it recomputes, instead of each day independently re-querying its own 30- or 56-day
 * lookback window.
 *
 * Holds a run-owned in-memory cache of derived [NightValues] keyed by [NightCacheKey]
 * (sessionId, sourceGeneration, scoringSnapshotId).
 */
class WalkForwardBaselineContext(
    val sessions: List<SleepSession>,
    private val scoringHistoryRepository: ScoringHistoryRepository? = null,
    val sourceGeneration: Long = 0L,
    val scoringSnapshotId: String = "",
) {
    private val nightlyValues = mutableMapOf<NightCacheKey, NightValues>()

    fun cachedCount(): Int = nightlyValues.size

    fun getCached(
        session: SleepSession,
        sourceGen: Long = sourceGeneration,
        snapshotId: String = scoringSnapshotId,
    ): NightValues? = nightlyValues[NightCacheKey(session.id, sourceGen, snapshotId)]

    suspend fun nightValues(
        session: SleepSession,
        sourceGen: Long = sourceGeneration,
        snapshotId: String = scoringSnapshotId,
    ): NightValues {
        val key = NightCacheKey(session.id, sourceGen, snapshotId)
        val cached = nightlyValues[key]
        if (cached != null) return cached

        val repo =
            checkNotNull(scoringHistoryRepository) {
                "ScoringHistoryRepository required to derive night values"
            }
        val hrProjection = repo.getSleepHrProjectionForSessions(listOf(session.id))
        val avgHrMap = repo.getAvgSleepHrForSessions(listOf(session.id))
        val rmssdMap = repo.getSleepRmssdForSessionsMap(listOf(session.id))

        val values =
            NightValues(
                orderedBpm = hrProjection.map { it.beatsPerMinute },
                averageBpm = avgHrMap[session.id],
                rmssdSamples = rmssdMap[session.id].orEmpty(),
            )
        nightlyValues[key] = values
        return values
    }

    suspend fun nightValuesForSessions(
        targetSessions: List<SleepSession>,
        sourceGen: Long = sourceGeneration,
        snapshotId: String = scoringSnapshotId,
    ): Map<String, NightValues> {
        val result = mutableMapOf<String, NightValues>()
        val missing = mutableListOf<SleepSession>()
        for (session in targetSessions) {
            val key = NightCacheKey(session.id, sourceGen, snapshotId)
            val cached = nightlyValues[key]
            if (cached != null) {
                result[session.id] = cached
            } else {
                missing.add(session)
            }
        }
        if (missing.isNotEmpty()) {
            val repo =
                checkNotNull(scoringHistoryRepository) {
                    "ScoringHistoryRepository required to derive night values"
                }
            val missingIds = missing.map { it.id }
            val hrProjection = repo.getSleepHrProjectionForSessions(missingIds).groupBy { it.sessionId }
            val avgHrMap = repo.getAvgSleepHrForSessions(missingIds)
            val rmssdMap = repo.getSleepRmssdForSessionsMap(missingIds)

            for (session in missing) {
                val key = NightCacheKey(session.id, sourceGen, snapshotId)
                val values =
                    NightValues(
                        orderedBpm = hrProjection[session.id].orEmpty().map { it.beatsPerMinute },
                        averageBpm = avgHrMap[session.id],
                        rmssdSamples = rmssdMap[session.id].orEmpty(),
                    )
                nightlyValues[key] = values
                result[session.id] = values
            }
        }
        return result
    }
}
