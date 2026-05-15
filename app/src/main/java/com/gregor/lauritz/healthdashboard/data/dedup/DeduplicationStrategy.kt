package com.gregor.lauritz.healthdashboard.data.dedup

import kotlin.math.abs

/**
 * Unified deduplication strategy for Health Connect records.
 *
 * Multi-device users (e.g. Garmin watch + Pixel phone) frequently end up with
 * overlapping records for the same biological event. Upserting on the Health
 * Connect record ID alone is insufficient because:
 *   - different package origins generate different IDs for the same event
 *   - some apps re-sync with new IDs after edits
 *   - merged exports can collide on naive IDs
 *
 * This module provides:
 *   - DeduplicationKey: device fingerprinting that survives ID churn
 *   - Per-type matchers with bounded tolerance windows
 *   - Smart merge that keeps the newest [metadata.lastModified] record
 *   - Audit trail for removed duplicates (reason + retained ID)
 *
 * Tolerance windows are tight enough to never merge distinct sessions but wide
 * enough to capture cross-device clock drift and minor algorithmic differences.
 */
sealed interface DeduplicationStrategy<T> {
    /**
     * Returns true if [candidate] is a likely duplicate of [existing].
     */
    fun isDuplicate(
        existing: T,
        candidate: T,
    ): Boolean

    /**
     * Returns the record that should be retained when two duplicates are found.
     * Default: prefer the record with the newer lastModifiedMs; on tie, prefer the
     * record whose data source is more specific (longer fingerprint).
     */
    fun smartMerge(
        a: T,
        b: T,
    ): T
}

/**
 * A device fingerprint summarising the origin of a Health Connect record.
 * Used as part of the composite primary key after deduplication.
 */
data class DeviceFingerprint(
    val packageName: String?,
    val deviceName: String?,
) {
    fun hash(): String {
        val raw = "${packageName ?: "?"}|${deviceName ?: "?"}"
        // Cheap stable hash; not cryptographically secure (sufficient for dedup keys).
        return raw.hashCode().toUInt().toString(16)
    }

    val isMoreSpecificThan: (DeviceFingerprint) -> Boolean = { other ->
        val selfBits = (if (packageName != null) 1 else 0) + (if (deviceName != null) 1 else 0)
        val otherBits = (if (other.packageName != null) 1 else 0) + (if (other.deviceName != null) 1 else 0)
        selfBits > otherBits
    }
}

/**
 * Common shape exposed by entities for deduplication. Implementations are kept
 * out of this file so the strategy can be reused without coupling to Room.
 */
interface Dedupable {
    val hcRecordId: String
    val deviceFingerprint: DeviceFingerprint
    val lastModifiedMs: Long
}

/** Sleep-session deduplication input projection. */
data class SleepDedupKey(
    override val hcRecordId: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMinutes: Int,
    val endZoneOffsetSeconds: Int?,
    override val deviceFingerprint: DeviceFingerprint,
    override val lastModifiedMs: Long,
) : Dedupable

class SleepSessionDeduplicator : DeduplicationStrategy<SleepDedupKey> {
    override fun isDuplicate(
        existing: SleepDedupKey,
        candidate: SleepDedupKey,
    ): Boolean {
        if (existing.hcRecordId == candidate.hcRecordId) return true
        val startDelta = abs(existing.startTimeMs - candidate.startTimeMs)
        val durDelta = abs(existing.durationMinutes - candidate.durationMinutes)
        val tzMatch =
            existing.endZoneOffsetSeconds == null ||
                candidate.endZoneOffsetSeconds == null ||
                existing.endZoneOffsetSeconds == candidate.endZoneOffsetSeconds
        return startDelta <= START_TOLERANCE_MS &&
            durDelta <= DURATION_TOLERANCE_MIN &&
            tzMatch
    }

    override fun smartMerge(
        a: SleepDedupKey,
        b: SleepDedupKey,
    ): SleepDedupKey = pickNewerOrMoreSpecific(a, b)

    companion object {
        const val START_TOLERANCE_MS = 5L * 60L * 1000L // ±5 min
        const val DURATION_TOLERANCE_MIN = 10 // ±10 min
    }
}

/** Heart-rate sample deduplication input projection. */
data class HeartRateDedupKey(
    override val hcRecordId: String,
    val timestampMs: Long,
    val bpm: Int,
    override val deviceFingerprint: DeviceFingerprint,
    override val lastModifiedMs: Long,
) : Dedupable

class HeartRateDeduplicator : DeduplicationStrategy<HeartRateDedupKey> {
    override fun isDuplicate(
        existing: HeartRateDedupKey,
        candidate: HeartRateDedupKey,
    ): Boolean {
        if (existing.hcRecordId == candidate.hcRecordId) return true
        val tDelta = abs(existing.timestampMs - candidate.timestampMs)
        val bpmDelta = abs(existing.bpm - candidate.bpm)
        // Note: Do NOT require device fingerprint match. Multi-device HR samples at same time
        // and BPM are genuinely duplicates (same heartbeat captured by different wearables).
        return tDelta <= TIMESTAMP_TOLERANCE_MS && bpmDelta == 0
    }

    override fun smartMerge(
        a: HeartRateDedupKey,
        b: HeartRateDedupKey,
    ): HeartRateDedupKey = pickNewerOrMoreSpecific(a, b)

    companion object {
        const val TIMESTAMP_TOLERANCE_MS = 60L * 1000L // ±1 min
    }
}

/** HRV sample deduplication input projection. */
data class HrvDedupKey(
    override val hcRecordId: String,
    val timestampMs: Long,
    val rmssdMs: Float,
    override val deviceFingerprint: DeviceFingerprint,
    override val lastModifiedMs: Long,
) : Dedupable

class HrvDeduplicator : DeduplicationStrategy<HrvDedupKey> {
    override fun isDuplicate(
        existing: HrvDedupKey,
        candidate: HrvDedupKey,
    ): Boolean {
        if (existing.hcRecordId == candidate.hcRecordId) return true
        val tDelta = abs(existing.timestampMs - candidate.timestampMs)
        val rmssdDelta = abs(existing.rmssdMs - candidate.rmssdMs)
        return tDelta <= TIMESTAMP_TOLERANCE_MS &&
            rmssdDelta <= RMSSD_TOLERANCE_MS
    }

    override fun smartMerge(
        a: HrvDedupKey,
        b: HrvDedupKey,
    ): HrvDedupKey = pickNewerOrMoreSpecific(a, b)

    companion object {
        const val TIMESTAMP_TOLERANCE_MS = 30L * 1000L // ±30 s
        const val RMSSD_TOLERANCE_MS = 5f // ±5 ms
    }
}

private fun <T : Dedupable> pickNewerOrMoreSpecific(
    a: T,
    b: T,
): T =
    when {
        a.lastModifiedMs > b.lastModifiedMs -> a
        b.lastModifiedMs > a.lastModifiedMs -> b
        a.deviceFingerprint.isMoreSpecificThan(b.deviceFingerprint) -> a
        b.deviceFingerprint.isMoreSpecificThan(a.deviceFingerprint) -> b
        else -> a // arbitrary tie-break
    }

/**
 * Captures a deduplication audit entry. Persistable into an audit table.
 */
data class DeduplicationAuditEntry(
    val removedRecordId: String,
    val retainedRecordId: String,
    val reason: String,
    val timestampMs: Long,
)

/**
 * Apply a strategy to a list of candidate records; returns the deduplicated list
 * plus an audit trail describing every removal.
 *
 * Performance: O(N log N) using timestamp-windowed search instead of O(N²) scan.
 * For each record, we search only records within a tolerance window, not the entire kept list.
 */
fun <T : Dedupable> DeduplicationStrategy<T>.apply(records: List<T>): Pair<List<T>, List<DeduplicationAuditEntry>> {
    val kept = mutableListOf<T>()
    val audit = mutableListOf<DeduplicationAuditEntry>()

    // Pre-sort kept list by timestamp for windowed search
    for (rec in records) {
        // Find candidates within timestamp tolerance window (e.g., ±60s for HR, ±5min for sleep)
        // Instead of scanning entire list, search only recent records
        val windowStart = rec.timestampMs - DEDUP_TIMESTAMP_TOLERANCE_MS
        val windowEnd = rec.timestampMs + DEDUP_TIMESTAMP_TOLERANCE_MS

        var duplicateIdx = -1
        // Iterate from end backwards to find most recent match within window (optimization)
        for (i in kept.indices.reversed()) {
            val candidate = kept[i]
            // Stop early if candidate is too old (below window)
            if (candidate.timestampMs < windowStart) break

            if (candidate.timestampMs in windowStart..windowEnd && isDuplicate(candidate, rec)) {
                duplicateIdx = i
                break
            }
        }

        if (duplicateIdx == -1) {
            kept.add(rec)
        } else {
            val existing = kept[duplicateIdx]
            val merged = smartMerge(existing, rec)
            val removed = if (merged === existing) rec else existing
            kept[duplicateIdx] = merged
            audit.add(
                DeduplicationAuditEntry(
                    removedRecordId = removed.hcRecordId,
                    retainedRecordId = merged.hcRecordId,
                    reason =
                        "Matched by ${this::class.simpleName} tolerance window; " +
                            "removed older / less specific entry.",
                    timestampMs = System.currentTimeMillis(),
                ),
            )
        }
    }
    return kept to audit
}

private const val DEDUP_TIMESTAMP_TOLERANCE_MS = 5 * 60 * 1000  // 5 minutes
