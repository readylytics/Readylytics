package com.gregor.lauritz.healthdashboard.widgets.glance.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceComposable
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.model.MetricType
import com.gregor.lauritz.healthdashboard.widgets.glance.utils.GlanceColorUtils
import com.gregor.lauritz.healthdashboard.widgets.glance.utils.GlanceMetricsFormatter

/**
 * Reusable metric card component for Glance widgets.
 * Displays a metric value with status-based coloring.
 *
 * Reuses dashboard card design language:
 * - Status-based background colors
 * - Material 3 semantic colors
 * - Metric label and value layout
 */
@Composable
@GlanceComposable
fun GlanceMetricCard(
    metricType: MetricType,
    value: Double,
    status: MetricStatus,
    label: String? = null,
    trend: Double? = null,
    onClickAction: androidx.glance.action.Action? = null,
    modifier: GlanceModifier = GlanceModifier,
) {
    val containerColor = ColorProvider(GlanceColorUtils.containerColor(status))
    val contentColor = ColorProvider(GlanceColorUtils.onContainerColor(status))
    val formattedValue = GlanceMetricsFormatter.formatValue(metricType, value)
    val unit = GlanceMetricsFormatter.getUnit(metricType)
    val displayLabel = label ?: metricType.displayName
    val trendSymbol = GlanceMetricsFormatter.formatTrend(trend)

    var cardModifier =
        modifier
            .fillMaxSize()
            .background(containerColor)

    if (onClickAction != null) {
        cardModifier = cardModifier.clickable(onClickAction)
    }

    Box(
        modifier = cardModifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                GlanceModifier
                    .fillMaxSize()
                    .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Label (top)
            Text(
                text = displayLabel,
                style =
                    TextStyle(
                        color = contentColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                    ),
            )

            // Status indicator dot (optional)
            if (status != MetricStatus.CALIBRATING) {
                Box(
                    modifier =
                        GlanceModifier
                            .padding(top = 2.dp)
                            .background(ColorProvider(GlanceColorUtils.gaugeColor(status))),
                ) {
                    // Dot is implicit from background
                }
            }

            // Value (center, large)
            Text(
                text = formattedValue,
                style =
                    TextStyle(
                        color = contentColor,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                modifier = GlanceModifier.padding(vertical = 4.dp),
            )

            // Unit (below value)
            if (unit.isNotEmpty()) {
                Text(
                    text = unit,
                    style =
                        TextStyle(
                            color = contentColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal,
                        ),
                )
            }

            // Trend (optional, at bottom)
            if (trendSymbol != null) {
                Text(
                    text = trendSymbol,
                    style =
                        TextStyle(
                            color = contentColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    modifier = GlanceModifier.padding(top = 2.dp),
                )
            }
        }
    }
}
