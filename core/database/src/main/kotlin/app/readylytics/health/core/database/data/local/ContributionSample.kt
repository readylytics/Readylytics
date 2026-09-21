package app.readylytics.health.core.database.data.local

/**
 * One deterministically reconstructed raw-sample equivalent of a stored per-source minute
 * contribution, produced by
 * [app.readylytics.health.core.databaseschema.data.local.entity.HrSourceMinuteContributionEntity
 * .reconstructEvidence]: the BPM value is exact (the histogram is a lossless frequency table), the
 * timestamp is a measured approximation bounded by the contribution's own first/last observed
 * sample times.
 */
internal data class ContributionSample(
    val timestampMs: Long,
    val beatsPerMinute: Int,
    val deviceName: String,
)
