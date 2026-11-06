package app.readylytics.health.feature.sleep

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.components.fixedXRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
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
            fixedXRangeProvider(
                xAxisRangeDays = xAxisRangeDays,
                minYOf = { _, _ ->
                    val minVal = remappedStartPoints.mapNotNull { it.value }.minOrNull() ?: 8.0f
                    floor(minVal.toDouble() - 1.0).coerceAtLeast(0.0)
                },
                maxYOf = { _, _ ->
                    val endVals =
                        remappedStartPoints
                            .zip(remappedSpanPoints) { start, span ->
                                val startVal = start.value
                                val spanVal = span.value
                                if (startVal != null && spanVal != null) startVal + spanVal else null
                            }.filterNotNull()
                    val maxVal = endVals.maxOrNull() ?: 20.0f
                    ceil(maxVal.toDouble() + 1.0).coerceAtMost(24.0)
                },
            )
        }

    val rightRangeProvider =
        remember(remappedActualPoints, xAxisRangeDays) {
            fixedXRangeProvider(
                xAxisRangeDays = xAxisRangeDays,
                minYOf = { _, _ -> 0.0 },
                maxYOf = { _, _ ->
                    val maxVal = remappedActualPoints.mapNotNull { it.value?.toDouble() }.maxOrNull() ?: 8.0
                    (ceil(maxVal / 2.0) * 2.0).coerceAtLeast(10.0)
                },
            )
        }

    return remember(leftRangeProvider, rightRangeProvider) {
        SleepTrendRangeProviders(leftRangeProvider, rightRangeProvider)
    }
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
