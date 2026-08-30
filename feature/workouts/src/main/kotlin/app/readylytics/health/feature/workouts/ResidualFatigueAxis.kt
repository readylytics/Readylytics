package app.readylytics.health.feature.workouts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.readylytics.health.core.model.domain.workouts.FatigueCurvePoint
import app.readylytics.health.core.model.domain.workouts.FatigueCurveRange
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24
private const val ONE_DAY_LABEL_STEP_HOURS = 4
private const val TICK_MATCH_TOLERANCE_MINUTES = 0.5
private const val MILLIS_PER_MINUTE = 60_000.0

/**
 * Axis tick positions (minutes from range start) paired with their rendered labels.
 *
 * Both are derived from real zoned instants rather than from multiples of 1440 minutes: on a DST
 * day a "day boundary" sits 23 or 25 hours after the previous one, so a fixed-minute tick would
 * land inside the wrong day and its label would name the wrong weekday.
 */
internal data class ResidualFatigueAxisTicks(
    val values: List<Double>,
    val labels: Map<Double, String>,
    val rangeStartMs: Long?,
    val zoneId: ZoneId,
    val range: FatigueCurveRange,
)

private fun minutesFromStart(
    startZdt: ZonedDateTime,
    tick: ZonedDateTime,
): Double = Duration.between(startZdt, tick).toMinutes().toDouble()

private fun rangeStartZdt(
    points: List<FatigueCurvePoint>,
    zoneId: ZoneId,
): ZonedDateTime? = points.firstOrNull()?.let { Instant.ofEpochMilli(it.timestampMs).atZone(zoneId) }

/**
 * Width of the x axis in minutes. Equals `days * 1440` except across a DST transition, where the
 * plotted range is genuinely an hour shorter or longer.
 */
internal fun residualFatigueMaxX(
    points: List<FatigueCurvePoint>,
    range: FatigueCurveRange,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Double {
    val startZdt = rangeStartZdt(points, zoneId) ?: return range.days * TOTAL_MINUTES_IN_DAY
    val endZdt = startZdt.toLocalDate().plusDays(range.days.toLong()).atStartOfDay(zoneId)
    return minutesFromStart(startZdt, endZdt)
}

internal fun residualFatigueAxisTicks(
    points: List<FatigueCurvePoint>,
    range: FatigueCurveRange,
    zoneId: ZoneId = ZoneId.systemDefault(),
): ResidualFatigueAxisTicks {
    val startZdt = rangeStartZdt(points, zoneId) ?: return fallbackAxisTicks(range, zoneId)
    val startDate = startZdt.toLocalDate()
    val ticks =
        if (range == FatigueCurveRange.ONE_DAY) {
            (0..HOURS_PER_DAY step ONE_DAY_LABEL_STEP_HOURS).map { hour ->
                val tick =
                    if (hour == HOURS_PER_DAY) {
                        startDate.plusDays(1).atStartOfDay(zoneId)
                    } else {
                        startDate.atTime(hour, 0).atZone(zoneId)
                    }
                minutesFromStart(startZdt, tick) to String.format(Locale.getDefault(), "%02d:00", hour)
            }
        } else {
            (0..range.days).map { dayIndex ->
                val tick = startDate.plusDays(dayIndex.toLong()).atStartOfDay(zoneId)
                minutesFromStart(startZdt, tick) to
                    tick.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))
            }
        }
    return ResidualFatigueAxisTicks(
        values = ticks.map { it.first },
        labels = ticks.toMap(),
        rangeStartMs = points.first().timestampMs,
        zoneId = zoneId,
        range = range,
    )
}

private fun fallbackAxisTicks(
    range: FatigueCurveRange,
    zoneId: ZoneId,
): ResidualFatigueAxisTicks {
    val values =
        if (range == FatigueCurveRange.ONE_DAY) {
            (0..HOURS_PER_DAY step ONE_DAY_LABEL_STEP_HOURS).map { it * MINUTES_PER_HOUR.toDouble() }
        } else {
            (0..range.days).map { it * TOTAL_MINUTES_IN_DAY }
        }
    return ResidualFatigueAxisTicks(
        values = values,
        labels = emptyMap(),
        rangeStartMs = null,
        zoneId = zoneId,
        range = range,
    )
}

@Composable
internal fun rememberResidualFatigueItemPlacer(ticks: ResidualFatigueAxisTicks): HorizontalAxis.ItemPlacer =
    remember(ticks) {
        val base = HorizontalAxis.ItemPlacer.aligned(spacing = { 1 }, addExtremeLabelPadding = true)
        object : HorizontalAxis.ItemPlacer by base {
            override fun getLabelValues(
                context: CartesianDrawingContext,
                visibleXRange: ClosedFloatingPointRange<Double>,
                fullXRange: ClosedFloatingPointRange<Double>,
                maxLabelWidth: Float,
            ): List<Double> = ticks.values.filter { it in fullXRange }
        }
    }

@Composable
internal fun rememberResidualFatigueValueFormatter(ticks: ResidualFatigueAxisTicks): CartesianValueFormatter =
    remember(ticks) {
        CartesianValueFormatter { _, v, _ ->
            // Match the precomputed ticks with a tolerance so a float round-trip cannot lose one,
            // and always fall back to a derived label: Vico also calls this for measurement values
            // that are not ticks at all, and treats a blank result as an error.
            ticks.labels.entries
                .minByOrNull { abs(it.key - v) }
                ?.takeIf { abs(it.key - v) <= TICK_MATCH_TOLERANCE_MINUTES }
                ?.value
                ?: derivedAxisLabel(v, ticks)
        }
    }

private fun derivedAxisLabel(
    minutesFromStart: Double,
    ticks: ResidualFatigueAxisTicks,
): String {
    val startMs = ticks.rangeStartMs
    if (startMs == null) {
        val hour = (minutesFromStart / MINUTES_PER_HOUR).roundToInt().coerceIn(0, HOURS_PER_DAY)
        return String.format(Locale.getDefault(), "%02d:00", hour)
    }
    val zdt =
        Instant
            .ofEpochMilli(startMs + (minutesFromStart * MILLIS_PER_MINUTE).toLong())
            .atZone(ticks.zoneId)
    val pattern = if (ticks.range == FatigueCurveRange.ONE_DAY) "HH:mm" else "EEE"
    return zdt.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}
