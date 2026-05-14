package com.gregor.lauritz.healthdashboard.widgets.glance.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceComposable
import androidx.glance.GlanceModifier
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
import com.gregor.lauritz.healthdashboard.widgets.glance.utils.GlanceColorUtils

/**
 * Circular score dial component for Glance widgets.
 * Displays a score (0-100) in circular format with status-based coloring.
 *
 * Simplified version of M3ScoreDial for widget constraints:
 * - Static rendering (no animation)
 * - Compact display (fits 2x2 grid)
 * - Status-based colors
 */
@Composable
@GlanceComposable
fun GlanceScoreDial(
    score: Double,
    label: String,
    status: MetricStatus,
    modifier: GlanceModifier = GlanceModifier,
) {
    val containerColor = GlanceColorUtils.containerColor(status)
    val contentColor = GlanceColorUtils.onContainerColor(status)
    val gaugeColor = GlanceColorUtils.gaugeColor(status)

    val scoreInt = score.toInt()
    val scoreStr =
        if (score == 0.0 && status == MetricStatus.CALIBRATING) {
            "--"
        } else {
            scoreInt.toString()
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(ColorProvider(containerColor)),
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
                text = label,
                style =
                    TextStyle(
                        color = ColorProvider(contentColor),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                modifier = GlanceModifier.padding(bottom = 4.dp),
            )

            // Score (center, large)
            Text(
                text = scoreStr,
                style =
                    TextStyle(
                        color = ColorProvider(contentColor),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                modifier = GlanceModifier.padding(vertical = 2.dp),
            )

            // Max score indicator
            Text(
                text = "/100",
                style =
                    TextStyle(
                        color = ColorProvider(contentColor),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Normal,
                    ),
            )

            // Status dot (bottom)
            if (status != MetricStatus.CALIBRATING) {
                Box(
                    modifier =
                        GlanceModifier
                            .background(ColorProvider(gaugeColor))
                            .padding(top = 2.dp),
                ) {
                    // Empty content
                }
            }
        }
    }
}
