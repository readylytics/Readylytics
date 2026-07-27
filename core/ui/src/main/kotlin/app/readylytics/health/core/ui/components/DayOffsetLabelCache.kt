package app.readylytics.health.core.ui.components

import app.readylytics.health.core.ui.common.DateFormatUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Memoizes x-axis day labels for a chart whose x-domain is day offsets from [rangeStartMs].
 *
 * Safe to cache without bounds: the horizontal item placer only ever emits day offsets in
 * 0..rangeDays-1 (<= ~180 entries), Vico measures and draws on a single thread, and the cache
 * instance lives and dies with the caller's remember(rangeStartMs) scope, so a range change
 * discards it. Output is byte-identical to formatting on every call -- non-integral values
 * already truncate through toLong().
 *
 * Zone and locale are captured once, for the lifetime of the caller's remember(rangeStartMs)
 * scope. The prior code re-read ZoneId.systemDefault() per label, but rangeStartMs and the
 * plotted day offsets are computed upstream against a fixed zone, so a mid-composition zone
 * change produced labels that disagreed with the data. Freezing both here keeps them consistent.
 */
internal class DayOffsetLabelCache(
    rangeStartMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
) {
    private val baseDate = Instant.ofEpochMilli(rangeStartMs).atZone(zone).toLocalDate()
    private val formatter = DateTimeFormatter.ofPattern(DateFormatUtils.DATE_FORMAT_SHORT, locale)
    private val cache = HashMap<Long, String>()

    fun label(value: Double): String {
        val offset = value.toLong()
        return cache.getOrPut(offset) { baseDate.plusDays(offset).format(formatter) }
    }
}
