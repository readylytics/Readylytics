package app.readylytics.health.core.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.LocalExtendedColors
import app.readylytics.health.core.model.domain.model.BucketZoneBands
import app.readylytics.health.core.model.domain.model.HealthZone
import app.readylytics.health.core.model.domain.model.ZoneBand
import com.patrykandpatrick.vico.compose.cartesian.decoration.HorizontalLine
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent

@Composable
internal fun rememberZoneBandDecoration(
    zoneBands: List<ZoneBand>?,
    bucketZoneBands: List<BucketZoneBands>?,
    minY: Double,
    maxY: Double,
    rangeDays: Int,
): ZoneBandDecoration {
    val extendedColors = LocalExtendedColors.current
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val errorContainer = MaterialTheme.colorScheme.errorContainer
    val chartZoneBands = zoneBands ?: emptyList()
    val colors = rememberZoneBandColors(chartZoneBands, extendedColors, primaryContainer, errorContainer)
    val zoneColor =
        remember(extendedColors, primaryContainer, errorContainer) {
            { zone: HealthZone ->
                when (zone) {
                    HealthZone.OPTIMAL -> primaryContainer.copy(alpha = ChartZoneAlphas.HIGH)
                    HealthZone.NEUTRAL -> extendedColors.neutralContainer.copy(alpha = ChartZoneAlphas.RESTING)
                    HealthZone.WARNING -> extendedColors.warningContainer.copy(alpha = ChartZoneAlphas.HIGH)
                    HealthZone.CRITICAL -> errorContainer.copy(alpha = ChartZoneAlphas.HIGH)
                }
            }
        }
    return remember(chartZoneBands, colors, minY, maxY, bucketZoneBands, rangeDays, zoneColor) {
        ZoneBandDecoration(
            zoneBands = chartZoneBands,
            bandColors = colors,
            minY = minY,
            maxY = maxY,
            bucketZoneBands = bucketZoneBands,
            rangeDays = rangeDays,
            zoneColor = zoneColor,
        )
    }
}

@Composable
internal fun rememberTrendChartDecorations(
    zoneBandDecoration: ZoneBandDecoration,
    shouldShowBaseline: Boolean,
    baselineValue: Float,
    baselineColor: Color,
    hasHistoricalBaseline: Boolean,
    bucketZoneBands: List<BucketZoneBands>?,
): List<Any> {
    val baselineLineComponent = rememberLineComponent(fill = Fill(baselineColor), thickness = 1.dp)
    return remember(
        zoneBandDecoration,
        shouldShowBaseline,
        baselineValue,
        baselineLineComponent,
        hasHistoricalBaseline,
        bucketZoneBands,
    ) {
        listOfNotNull(
            zoneBandDecoration,
            if (shouldShowBaseline && !hasHistoricalBaseline) {
                HorizontalLine(
                    y = { baselineValue.toDouble() },
                    line = baselineLineComponent,
                )
            } else {
                null
            },
        )
    }
}
