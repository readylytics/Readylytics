package com.gregor.lauritz.healthdashboard.widgets.glance.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceComposable
import androidx.glance.GlanceModifier
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.wrapContentHeight
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.widgets.glance.utils.GlanceColorUtils

/**
 * Horizontal steps progress bar component for Glance widgets.
 * Displays current steps vs goal with visual progress indication.
 */
@Composable
@GlanceComposable
fun GlanceStepsBar(
    currentSteps: Long,
    goalSteps: Long,
    modifier: GlanceModifier = GlanceModifier,
    onClickAction: androidx.glance.action.Action? = null,
) {
    val percentage =
        if (goalSteps > 0) {
            (currentSteps * 100) / goalSteps
        } else {
            0
        }

    // Determine status based on percentage
    val status =
        when {
            percentage >= 100 -> MetricStatus.OPTIMAL
            percentage >= 75 -> MetricStatus.NEUTRAL
            percentage >= 50 -> MetricStatus.WARNING
            else -> MetricStatus.POOR
        }

    val barColor = GlanceColorUtils.gaugeColor(status)
    val backgroundColor = Color(0xFFE0E0E0) // Light gray background

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(12.dp),
    ) {
        // Title row
        Row(
            modifier =
                GlanceModifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Steps",
                style =
                    TextStyle(
                        color = ColorProvider(Color(0xFF333333)),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                    ),
            )

            Spacer(modifier = GlanceModifier.defaultWeight())

            Text(
                text = "$percentage%",
                style =
                    TextStyle(
                        color = ColorProvider(barColor),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    ),
            )
        }

        // Progress bar
        Box(
            modifier =
                GlanceModifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .padding(vertical = 4.dp)
                    .background(ColorProvider(backgroundColor)),
        ) {
            // Filled portion
            Box(
                modifier =
                    GlanceModifier
                        .fillMaxWidth() // Simplified
                        .height(28.dp)
                        .background(ColorProvider(barColor)),
            ) {
                // Empty content
            }
        }

        // Stats row
        Row(
            modifier =
                GlanceModifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
        ) {
            Text(
                text = "$currentSteps",
                style =
                    TextStyle(
                        color = ColorProvider(Color(0xFF666666)),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Normal,
                    ),
            )

            Spacer(modifier = GlanceModifier.defaultWeight())

            Text(
                text = "Goal: $goalSteps",
                style =
                    TextStyle(
                        color = ColorProvider(Color(0xFF999999)),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Normal,
                    ),
            )
        }
    }
}
