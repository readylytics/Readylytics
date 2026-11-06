package app.readylytics.health.feature.workouts

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
import kotlin.math.ceil

internal typealias AcwrRangeProviders = AcwrChartRangeProviders

internal class AcwrChartRangeProviders(
    val trimpRangeProvider: CartesianLayerRangeProvider,
    val ratioRangeProvider: CartesianLayerRangeProvider,
    val ratioAxisMax: Double,
)

@Composable
internal fun rememberAcwrRangeProviders(
    remappedTrimpPoints: List<DailyDataPoint>,
    remappedRatioPoints: List<DailyDataPoint>,
    xAxisRangeDays: Int,
): AcwrChartRangeProviders {
    val trimpRangeProvider =
        remember(remappedTrimpPoints, xAxisRangeDays) {
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

                override fun getMaxY(
                    minY: Double,
                    maxY: Double,
                    extraStore: ExtraStore,
                ): Double = (ceil(maxY / 25.0) * 25.0).coerceAtLeast(100.0)

                override fun getMinY(
                    minY: Double,
                    maxY: Double,
                    extraStore: ExtraStore,
                ): Double = 0.0
            }
        }

    val ratioRangeProvider =
        remember(remappedRatioPoints, xAxisRangeDays) {
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

                override fun getMaxY(
                    minY: Double,
                    maxY: Double,
                    extraStore: ExtraStore,
                ): Double = (ceil(maxY / 0.5) * 0.5).coerceAtLeast(2.0)

                override fun getMinY(
                    minY: Double,
                    maxY: Double,
                    extraStore: ExtraStore,
                ): Double = 0.0
            }
        }

    val ratioAxisMax =
        remember(remappedRatioPoints) {
            val dataMax = remappedRatioPoints.mapNotNull { it.value?.toDouble() }.maxOrNull() ?: 0.0
            (ceil(dataMax / 0.5) * 0.5).coerceAtLeast(2.0)
        }

    return remember(trimpRangeProvider, ratioRangeProvider, ratioAxisMax) {
        AcwrChartRangeProviders(trimpRangeProvider, ratioRangeProvider, ratioAxisMax)
    }
}

@Composable
internal fun rememberAcwrPeriodLabels(
    trimpPoints: List<DailyDataPoint>,
    rangeStartMs: Long,
    granularity: TrendGranularity,
): List<String> {
    val ordinalLabel = rememberPeriodOrdinalLabel(granularity)
    return remember(trimpPoints, rangeStartMs, granularity, ordinalLabel) {
        if (granularity == TrendGranularity.DAILY) {
            emptyList()
        } else {
            trimpPoints.map { point ->
                val date = ChartUtils.dayOffsetToLocalDate(point.dayOffset, rangeStartMs)
                periodLabelFor(granularity, date, ordinalLabel)
            }
        }
    }
}

@Composable
internal fun rememberAcwrRemappedPoints(
    points: List<DailyDataPoint>,
    granularity: TrendGranularity,
): List<DailyDataPoint> =
    remember(points, granularity) {
        if (granularity == TrendGranularity.DAILY) points else points.mapIndexed { i, p -> p.copy(dayOffset = i) }
    }

@Composable
internal fun rememberAcwrModelProducer(
    remappedTrimpPoints: List<DailyDataPoint>,
    remappedRatioPoints: List<DailyDataPoint>,
): CartesianChartModelProducer {
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(remappedTrimpPoints, remappedRatioPoints) {
        modelProducer.runTransaction {
            val validTrimp = remappedTrimpPoints.filter { it.value != null }
            if (validTrimp.isNotEmpty()) {
                columnModel {
                    series(
                        x = validTrimp.map { it.dayOffset },
                        y = validTrimp.mapNotNull { it.value?.toDouble() },
                    )
                }
            }
            val validRatio = remappedRatioPoints.filter { it.value != null }
            if (validRatio.isNotEmpty()) {
                lineModel {
                    series(
                        x = validRatio.map { it.dayOffset },
                        y = validRatio.mapNotNull { it.value?.toDouble() },
                    )
                }
            }
        }
    }
    return modelProducer
}

@Composable
internal fun rememberAcwrXAxisFormatter(
    periodLabels: List<String>,
    rangeStartMs: Long,
    granularity: TrendGranularity,
): CartesianValueFormatter =
    if (granularity == TrendGranularity.DAILY) {
        ChartDefaults.rememberPeriodFormatter(rangeStartMs, granularity)
    } else {
        val fallback = periodLabels.firstOrNull().orEmpty()
        remember(periodLabels) {
            CartesianValueFormatter { _, value, _ ->
                periodLabels.getOrElse(value.toInt()) { fallback }
            }
        }
    }
