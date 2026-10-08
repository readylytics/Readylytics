package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.changes.Change
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.sync.HealthChangeIngestionStore
import app.readylytics.health.core.model.domain.sync.SessionSpans

/**
 * Resolves a page's final per-ID action: when the same HC record ID appears more than once in one
 * page (e.g. an upsert followed by a deletion, or vice versa), only the LAST event for that ID
 * must determine whether it ends up deleted or upserted -- an earlier event for the same ID must
 * never re-apply after a later one supersedes it.
 */
internal fun lastEventPerId(changes: List<Change>): Map<String, Change> {
    val lastById = linkedMapOf<String, Change>()
    for (change in changes) {
        val id =
            when (change) {
                is UpsertionChange -> change.record.metadata.id
                is DeletionChange -> change.recordId
                else -> null
            } ?: continue
        lastById[id] = change
    }
    return lastById
}

/**
 * R2-HC-003: one `sessionSpansOverlapping` call for the whole page's time range, instead of one
 * per HEART_RATE/HRV record. Only fetched for the two data types that consume spans. Health
 * Connect's per-type change token guarantees a HEART_RATE/HRV page never contains another record
 * type, but this filters defensively via mapNotNull: one unexpected record must skip cleanly,
 * never abort `applyPendingChanges()` for every data type.
 */
internal suspend fun HealthChangeIngestionStore.pageSessionSpans(
    dataType: HealthDataType,
    changes: List<Change>,
): SessionSpans {
    val ranges =
        if (dataType == HealthDataType.HEART_RATE || dataType == HealthDataType.HRV) {
            changes.filterIsInstance<UpsertionChange>().mapNotNull { recordTimeRangeMs(it.record) }
        } else {
            emptyList()
        }
    if (ranges.isEmpty()) return SessionSpans(emptyList(), emptyList())
    return sessionSpansOverlapping(ranges.minOf { it.first }, ranges.maxOf { it.second })
}

private fun recordTimeRangeMs(record: Record): Pair<Long, Long>? =
    when (record) {
        is HeartRateRecord -> record.startTime.toEpochMilli() to record.endTime.toEpochMilli()
        is HeartRateVariabilityRmssdRecord -> record.time.toEpochMilli().let { it to it }
        else -> null
    }
