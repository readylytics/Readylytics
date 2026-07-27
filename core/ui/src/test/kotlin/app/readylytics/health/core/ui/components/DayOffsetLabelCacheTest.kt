package app.readylytics.health.core.ui.components

import app.readylytics.health.core.ui.common.DateFormatUtils
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class DayOffsetLabelCacheTest {
    /**
     * Golden oracle: a private copy of the pre-caching inline expression from
     * `ChartDefaults.rememberDayOffsetFormatter`, kept verbatim so the cached implementation can be
     * proven byte-identical to the code it replaces, for every input.
     */
    private fun reference(
        rangeStartMs: Long,
        value: Double,
        zone: ZoneId,
        locale: Locale,
    ): String {
        val formatter = DateTimeFormatter.ofPattern(DateFormatUtils.DATE_FORMAT_SHORT, locale)
        return Instant
            .ofEpochMilli(rangeStartMs)
            .atZone(zone)
            .toLocalDate()
            .plusDays(value.toLong())
            .format(formatter)
    }

    private fun epochMs(
        year: Int,
        month: Int,
        day: Int,
        zone: ZoneId,
    ): Long =
        java.time.LocalDate
            .of(year, month, day)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    @Test
    fun `golden vs reference across fixtures and offsets`() {
        val zone = ZoneId.of("UTC")
        val locale = Locale.US
        val fixtures =
            listOf(
                epochMs(2026, 6, 15, zone), // mid-month
                epochMs(2026, 1, 31, zone), // month boundary
                epochMs(2025, 12, 31, zone), // year boundary
                epochMs(2024, 2, 28, zone), // leap-day-crossing start (2024 is a leap year)
            )

        for (rangeStartMs in fixtures) {
            val cache = DayOffsetLabelCache(rangeStartMs, zone, locale)
            for (offsetTenths in 0..1790) {
                val value = offsetTenths / 10.0
                assertEquals(
                    reference(rangeStartMs, value, zone, locale),
                    cache.label(value),
                    "mismatch for rangeStartMs=$rangeStartMs value=$value",
                )
            }
        }
    }

    @Test
    fun `non-integral values truncate toward zero same as toLong`() {
        val zone = ZoneId.of("UTC")
        val locale = Locale.US
        val rangeStartMs = epochMs(2026, 6, 15, zone)
        val cache = DayOffsetLabelCache(rangeStartMs, zone, locale)

        assertEquals(cache.label(3.0), cache.label(3.7))
        assertEquals(cache.label(3.0), cache.label(3.999))
        assertEquals(cache.label(-0.5), cache.label(0.0))
    }

    @Test
    fun `caching returns same String instance for repeated values`() {
        val zone = ZoneId.of("UTC")
        val locale = Locale.US
        val rangeStartMs = epochMs(2026, 6, 15, zone)
        val cache = DayOffsetLabelCache(rangeStartMs, zone, locale)

        val first = cache.label(5.0)
        val second = cache.label(5.0)
        assertSame(first, second)

        val different = cache.label(6.0)
        assertNotSame(first, different)
    }

    @Test
    fun `default constructor arguments pin to systemDefault zone and locale`() {
        // Production (ChartDefaults.rememberDayOffsetFormatter) always constructs via the
        // no-arg-zone-locale path. Pin it against ZoneId.systemDefault() / Locale.getDefault()
        // explicitly, so a change to the defaults doesn't silently drift from what's shipped.
        val rangeStartMs = epochMs(2026, 6, 15, ZoneId.systemDefault())
        val defaultCache = DayOffsetLabelCache(rangeStartMs)
        val explicitCache = DayOffsetLabelCache(rangeStartMs, ZoneId.systemDefault(), Locale.getDefault())

        for (offset in 0..179) {
            assertEquals(explicitCache.label(offset.toDouble()), defaultCache.label(offset.toDouble()))
        }
    }

    @Test
    fun `labels advance one calendar day per offset across a DST transition week`() {
        // Europe/Berlin spring-forward DST transition is the last Sunday of March; 2026-03-29.
        val zone = ZoneId.of("Europe/Berlin")
        val locale = Locale.US
        val rangeStartMs = epochMs(2026, 3, 27, zone)
        val cache = DayOffsetLabelCache(rangeStartMs, zone, locale)

        val formatter = DateTimeFormatter.ofPattern(DateFormatUtils.DATE_FORMAT_SHORT, locale)
        val baseDate =
            Instant
                .ofEpochMilli(rangeStartMs)
                .atZone(zone)
                .toLocalDate()

        for (offset in 0..6) {
            val expected = baseDate.plusDays(offset.toLong()).format(formatter)
            assertEquals(expected, cache.label(offset.toDouble()))
        }
    }
}
