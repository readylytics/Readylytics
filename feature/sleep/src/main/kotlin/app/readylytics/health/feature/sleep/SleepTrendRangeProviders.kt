package app.readylytics.health.feature.sleep

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import app.readylytics.health.core.ui.common.ChartUtils
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.TrendGranularity
import app.readylytics.health.core.ui.common.periodLabelFor
import app.readylytics.health.core.ui.common.rememberPeriodOrdinalLabel
import app.readylytics.health.core.ui.components.ChartDefaults
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import java.time.ZoneId
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal class SleepTrendRangeProviders(
    val leftRangeProvider: CartesianLayerRangeProvider,
    val rightRangeProvider: CartesianLayerRangeProvider,
)

@Composable
internal fun rememberSleepTrendRangeProviders(
    remappedStartPoints: List<DailyDataPoint>,
    remappedSpanPoints: List<DailyDataPoint>,
    remappedActualPoints: List<DailyDataPoint>,
    xAxisRangeDays: Int,
): SleepTrendRangeProviders {
    val leftRangeProvider =
        remember(remappedStartPoints, remappedSpanPoints, xAxisRangeDays) {
            object : CartesianLayerRangeProvider {
                override fun getMinX(
                    minX: Double,
                    maxX: Double,
                    extraStore: ExtraStore,
                ) = 0.0

                override fun getMaxX(
                    minX: Double,
                    maxX: Double,
                    extraStore: ExtraStore,
                ) = (xAxisRangeDays - 1).toDouble()

                override fun getMinY(
                    minY: Double,
                    maxY: Double,
                    extraStore: ExtraStore,
                ): Double {
                    val startVals = remappedStartPoints.mapNotNull { it.value }
                    val minVal = startVals.minOrNull() ?: 8.0f
                    return (floor(minVal.toDouble() - 1.0)).coerceAtLeast(0.0)
                }

                override fun getMaxY(
                    minY: Double,
                    maxY: Double,
                    extraStore: ExtraStore,
                ): Double {
                    val endVals =
                        remappedStartPoints
                            .zip(remappedSpanPoints) { start, span ->
                                val startVal = start.value
                                val spanVal = span.value
                                if (startVal != null && spanVal != null) startVal + spanVal else null
                            }.filterNotNull()
                    val maxVal = endVals.maxOrNull() ?: 20.0f
                    return (ceil(maxVal.toDouble() + 1.0)).coerceAtMost(24.0)
                }
            }
        }

    val rightRangeProvider =
        remember(remappedActualPoints, xAxisRangeDays) {
            object : CartesianLayerRangeProvider {
                override fun getMinX(
                    minX: Double,
                    maxX: Double,
                    extraStore: ExtraStore,
                ) = 0.0

                override fun getMaxX(
                    minX: Double,
                    maxX: Double,
                    extraStore: ExtraStore,
                ) = (xAxisRangeDays - 1).toDouble()

                override fun getMinY(
                    minY: Double,
                    maxY: Double,
                    extraStore: ExtraStore,
                ) = 0.0

                override fun getMaxY(
                    minY: Double,
                    maxY: Double,
                    extraStore: ExtraStore,
                ): Double {
                    val vals = remappedActualPoints.mapNotNull { it.value?.toDouble() }
                    val maxVal = vals.maxOrNull() ?: 8.0
                    return (ceil(maxVal / 2.0) * 2.0).coerceAtLeast(10.0)
                }
            }
        }

    return remember(leftRangeProvider, rightRangeProvider) {
        SleepTrendRangeProviders(leftRangeProvider, rightRangeProvider)
    }
}

@Composable
internal fun rememberSleepTrendPeriodLabels(
    startOffsetPoints: List<DailyDataPoint>,
    rangeStartMs: Long,
    granularity: TrendGranularity,
    scoringZoneId: ZoneId,
): List<String> {
    val ordinalLabel = rememberPeriodOrdinalLabel(granularity)
    return remember(startOffsetPoints, rangeStartMs, granularity, scoringZoneId, ordinalLabel) {
        if (granularity == TrendGranularity.DAILY) {
            emptyList()
        } else {
            startOffsetPoints.map { point ->
                val date = ChartUtils.dayOffsetToLocalDate(point.dayOffset, rangeStartMs, scoringZoneId)
                periodLabelFor(granularity, date, ordinalLabel)
            }
        }
    }
}

@Composable
internal fun rememberSleepTrendRemappedPoints(
    points: List<DailyDataPoint>,
    granularity: TrendGranularity,
): List<DailyDataPoint> =
    remember(points, granularity) {
        if (granularity == TrendGranularity.DAILY) points else points.mapIndexed { i, p -> p.copy(dayOffset = i) }
    }

@Composable
internal fun rememberSleepTrendModelProducer(
    remappedStartPoints: List<DailyDataPoint>,
    remappedSpanPoints: List<DailyDataPoint>,
    remappedActualPoints: List<DailyDataPoint>,
): CartesianChartModelProducer {
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(remappedStartPoints, remappedSpanPoints, remappedActualPoints) {
        modelProducer.runTransaction {
            val validStart = remappedStartPoints.filter { it.value != null }
            val validSpan = remappedSpanPoints.filter { it.value != null }
            if (validStart.isNotEmpty() && validSpan.isNotEmpty()) {
                columnModel {
                    series(validStart.map { it.dayOffset }, validStart.mapNotNull { it.value?.toDouble() })
                    series(validSpan.map { it.dayOffset }, validSpan.mapNotNull { it.value?.toDouble() })
                }
            }
            val validActual = remappedActualPoints.filter { it.value != null }
            if (validActual.isNotEmpty()) {
                lineModel {
                    series(validActual.map { it.dayOffset }, validActual.mapNotNull { it.value?.toDouble() })
                }
            }
        }
    }
    return modelProducer
}

@Composable
internal fun rememberSleepTrendXAxisFormatter(
    periodLabels: List<String>,
    rangeStartMs: Long,
    granularity: TrendGranularity,
    scoringZoneId: ZoneId,
): CartesianValueFormatter =
    if (granularity == TrendGranularity.DAILY) {
        ChartDefaults.rememberPeriodFormatter(rangeStartMs, granularity, scoringZoneId)
    } else {
        remember(periodLabels) {
            val fallback = periodLabels.firstOrNull().orEmpty()
            CartesianValueFormatter { _, value, _ -> periodLabels.getOrElse(value.toInt()) { fallback } }
        }
    }

internal fun formatSleepTrendHour(value: Double): String {
    val hour = (12 + value.roundToInt()).mod(24)
    val amPm = if (hour >= 12) "PM" else "AM"
    val displayHour =
        when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
    return String.format(Locale.getDefault(), "%d %s", displayHour, amPm)
}
