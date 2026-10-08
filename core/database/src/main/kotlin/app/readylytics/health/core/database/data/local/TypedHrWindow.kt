package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import java.util.PriorityQueue

internal data class TypedHrWindow(val recordType: String, val startMs: Long, val endMs: Long)

/** Retains at most one SQL page per tier and one output page. */
internal suspend fun AuthoritativeHeartRateReader.readTypePages(
    window: TypedHrWindow,
    limit: Int,
    onPage: suspend (List<HeartRateRecordEntity>) -> Unit,
) {
    require(limit > 0)
    val hot = HotTypeCursor(this, window, limit)
    val warm = WarmTypeCursor(this, window, limit)
    var raw = hot.next()
    var reconstructed = warm.next()
    var output = ArrayList<HeartRateRecordEntity>(limit)
    while (raw != null || reconstructed != null) {
        currentCoroutineContext().ensureActive()
        if (raw != null && (reconstructed == null || raw.timestampMs <= reconstructed.timestampMs)) {
            output.add(raw)
            raw = hot.next()
        } else {
            output.add(checkNotNull(reconstructed))
            reconstructed = warm.next()
        }
        if (output.size == limit) {
            onPage(output)
            output = ArrayList(limit)
            yield()
        }
    }
    if (output.isNotEmpty()) onPage(output)
}

private class HotTypeCursor(
    private val reader: AuthoritativeHeartRateReader,
    private val window: TypedHrWindow,
    private val limit: Int,
) {
    private var rows = emptyList<HeartRateRecordEntity>()
    private var index = 0
    private var after: HeartRateRecordEntity? = null
    private var exhausted = false

    suspend fun next(): HeartRateRecordEntity? {
        if (index == rows.size && !exhausted) {
            rows = reader.heartRateDao.visibleTypePage(
                window.recordType, window.startMs, window.endMs, after?.timestampMs, after?.sourceRecordRef, limit,
            )
            index = 0
            exhausted = rows.size < limit
            after = rows.lastOrNull()
        }
        return if (index < rows.size) rows[index++] else null
    }
}

private class BucketCursor(
    private val reader: AuthoritativeHeartRateReader,
    private val window: TypedHrWindow,
    private val limit: Int,
) {
    private var rows = emptyList<HrMinuteBucketEntity>()
    private var index = 0
    private var after: HrMinuteBucketEntity? = null
    private var exhausted = false

    suspend fun next(): HrMinuteBucketEntity? {
        if (index == rows.size && !exhausted) {
            rows = reader.minuteBucketDao.visibleTypePage(
                window.recordType, window.startMs, window.endMs,
                after?.bucketStartMs, after?.sessionId, after?.deviceName, limit,
            )
            index = 0
            exhausted = rows.size < limit
            after = rows.lastOrNull()
            yield()
        }
        return if (index < rows.size) rows[index++] else null
    }
}

private class WarmPoint(val bucket: HrMinuteBucketEntity, var index: Int = 0) {
    private val valueAt = bucket.sampleValueAtIndex()
    val timestamp: Long
        get() = bucket.bucketStartMs + if (bucket.sampleCount > 1) index * (60_000L / bucket.sampleCount) else 0L

    fun record() = HeartRateRecordEntity(
        sourceRecordRef = 0,
        timestampMs = timestamp,
        beatsPerMinute = valueAt(index),
        recordType = bucket.recordType,
        sessionId = bucket.sessionId.ifEmpty { null },
        deviceName = bucket.deviceName.ifEmpty { null },
    )

    fun copy() = WarmPoint(bucket, index)
}

private val warmOrder = compareBy<WarmPoint>(
    { it.timestamp }, { it.bucket.bucketStartMs }, { it.bucket.recordType },
    { it.bucket.sessionId }, { it.bucket.deviceName }, { it.index },
)

/**
 * Normal minutes retain <= limit bucket cursors. Pathological minutes with more slices rescan
 * bounded metadata pages to select the next limit points, trading time for bounded retained heap.
 */
private class WarmTypeCursor(
    private val reader: AuthoritativeHeartRateReader,
    private val window: TypedHrWindow,
    private val limit: Int,
) {
    private val buckets = BucketCursor(reader, window, limit)
    private var pending: HrMinuteBucketEntity? = null
    private val points = PriorityQueue(warmOrder)
    private var fallbackMinute: Long? = null
    private var fallbackAfter: WarmPoint? = null
    private var fallbackPage = emptyList<WarmPoint>()
    private var fallbackIndex = 0

    suspend fun next(): HeartRateRecordEntity? {
        var result: HeartRateRecordEntity? = null
        while (result == null) {
            currentCoroutineContext().ensureActive()
            result = if (fallbackMinute != null) nextFallback() else null
            if (result == null && points.isNotEmpty()) {
                val point = points.remove()
                result = point.record()
                point.index++
                if (point.index < point.bucket.sampleCount) points.add(point)
            }
            if (result == null && !loadMinute()) break
        }
        return result
    }

    private suspend fun loadMinute(): Boolean {
        val first = pending ?: buckets.next() ?: return false
        val minute = first.bucketStartMs
        var bucket: HrMinuteBucketEntity? = first
        var count = 0
        while (bucket != null && bucket.bucketStartMs == minute) {
            count++
            if (count <= limit && bucket.sampleCount > 0) points.add(WarmPoint(bucket))
            bucket = buckets.next()
        }
        pending = bucket
        if (count > limit) {
            points.clear()
            fallbackMinute = minute
            fallbackAfter = null
            fallbackPage = emptyList()
            fallbackIndex = 0
        }
        return true
    }

    private suspend fun nextFallback(): HeartRateRecordEntity? {
        if (fallbackIndex == fallbackPage.size) {
            fallbackPage = selectFallbackPage()
            fallbackIndex = 0
            if (fallbackPage.isEmpty()) {
                fallbackMinute = null
                fallbackAfter = null
                return null
            }
            fallbackAfter = fallbackPage.last()
        }
        return fallbackPage[fallbackIndex++].record()
    }

    private suspend fun selectFallbackPage(): List<WarmPoint> {
        val minute = checkNotNull(fallbackMinute)
        // Bucket-end overlap includes the previous minute; its start is checked below.
        val scan = BucketCursor(reader, window.copy(startMs = maxOf(window.startMs, minute), endMs = minute), limit)
        val selected = PriorityQueue(warmOrder.reversed())
        var bucket = scan.next()
        while (bucket != null) {
            if (bucket.bucketStartMs == minute) addCandidates(bucket, selected)
            bucket = scan.next()
        }
        return selected.toList().sortedWith(warmOrder)
    }

    private fun addCandidates(bucket: HrMinuteBucketEntity, selected: PriorityQueue<WarmPoint>) {
        val point = WarmPoint(bucket)
        var low = 0
        var high = bucket.sampleCount
        val after = fallbackAfter
        if (after != null) {
            while (low < high) {
                val middle = (low + high) ushr 1
                point.index = middle
                if (warmOrder.compare(point, after) <= 0) low = middle + 1 else high = middle
            }
        }
        point.index = low
        var added = 0
        while (point.index < bucket.sampleCount && added < limit) {
            if (selected.size == limit && warmOrder.compare(point, selected.peek()) >= 0) break
            if (selected.size == limit) selected.remove()
            selected.add(point.copy())
            point.index++
            added++
        }
    }
}
