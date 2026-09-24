package app.readylytics.health.core.model.domain.repository

import app.readylytics.health.core.model.domain.model.HrRangeAggregate
import kotlinx.coroutines.flow.Flow

data class HeartRateRecordData(
    val id: String,
    val timestampMs: Long,
    val beatsPerMinute: Int,
    val recordType: String,
    val sessionId: String? = null,
    val deviceName: String? = null,
)

data class HrvRecordData(
    val id: String,
    val timestampMs: Long,
    val rmssdMs: Float,
    val recordType: String,
    val sessionId: String? = null,
    val deviceName: String? = null,
)

enum class HeartRateResolution { RAW, RECONSTRUCTED }

data class HeartRateSeries(
    val points: List<HeartRateRecordData>,
    val resolution: HeartRateResolution,
)

interface HeartRateRepository {
    suspend fun getMinHrInRange(
        startTimeMs: Long,
        endTimeMs: Long,
    ): Int?

    suspend fun getByTimeRange(
        startTimeMs: Long,
        endTimeMs: Long,
    ): List<HeartRateRecordData>

    /**
     * WP-17/R2-UI-002: hot ∪ warm-tier heart-rate samples for one sleep session, matching
     * [observeTimelineWithResolution]'s tier-merge contract but scoped to a session id instead of
     * a time range. The raw side stays deliberately unfiltered (OD-3: implausible spikes still
     * render, since this backs the as-sensor-recorded chart). A session whose raw rows have all
     * rolled off past [DataRollupManager]'s hot/warm cutoff previously rendered an empty chart;
     * this merges in the warm-tier reconstruction instead, labelled [HeartRateResolution.RECONSTRUCTED].
     */
    fun observeSleepHrTimelineForSession(sessionId: String): Flow<HeartRateSeries>

    fun observeSleepHrvSince(fromMs: Long): Flow<List<HrvRecordData>>

    fun observeSleepHrvTimelineForSession(sessionId: String): Flow<List<HrvRecordData>>

    fun observeByTimeRange(
        startMs: Long,
        endMs: Long,
    ): Flow<List<HeartRateRecordData>>

    /** PERF-005/WP-23: SQL-aggregated min/max/avg/count observable for day-summary consumers. */
    fun observeAggregateByTimeRange(
        startMs: Long,
        endMs: Long,
    ): Flow<HrRangeAggregate?>

    /**
     * WP-17/R2-UI-002: hot ∪ warm-tier heart-rate samples in `[startTimeMs, endTimeMs]`.
     * [HeartRateSeries.resolution] is [HeartRateResolution.RAW] when every returned point came
     * from the hot (raw) tier, [HeartRateResolution.RECONSTRUCTED] when any warm-tier bucket
     * contributed a reconstructed point -- callers surface this so charts can label
     * lower-fidelity data past the hot/warm boundary instead of rendering it as if it were raw.
     */
    suspend fun getRecoveryWindowSamples(startTimeMs: Long, endTimeMs: Long): HeartRateSeries

    /** WP-17: observable equivalent of [getRecoveryWindowSamples], hot ∪ warm, deduped on emit. */
    fun observeTimelineWithResolution(startMs: Long, endMs: Long): Flow<HeartRateSeries>
}
