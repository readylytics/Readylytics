package app.readylytics.health.feature.workouts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.components.fixedXRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import kotlin.math.ceil

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
            fixedXRangeProvider(
                xAxisRangeDays = xAxisRangeDays,
                minYOf = { _, _ -> 0.0 },
                maxYOf = { _, maxY -> (ceil(maxY / 25.0) * 25.0).coerceAtLeast(100.0) },
            )
        }

    val ratioRangeProvider =
        remember(remappedRatioPoints, xAxisRangeDays) {
            fixedXRangeProvider(
                xAxisRangeDays = xAxisRangeDays,
                minYOf = { _, _ -> 0.0 },
                maxYOf = { _, maxY -> (ceil(maxY / 0.5) * 0.5).coerceAtLeast(2.0) },
            )
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
