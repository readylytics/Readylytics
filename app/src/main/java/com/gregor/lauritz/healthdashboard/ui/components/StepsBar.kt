package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.model.stepsStatus
import java.text.NumberFormat

// stepGoal fills bar to 75% width — mirrors PAI bar design
private fun barMax(stepGoal: Int): Float = stepGoal / 0.75f

@Composable
fun StepsBar(
    stepCount: Int?,
    stepGoal: Int,
    modifier: Modifier = Modifier,
) {
    val count = stepCount ?: 0
    val status = if (stepCount != null) stepsStatus(count, stepGoal) else MetricStatus.CALIBRATING
    val fillColor = status.gaugeColor()
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val outlineColor = MaterialTheme.colorScheme.outlineVariant

    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().height(28.dp)) {
            val totalWidth = size.width
            val barHeight = size.height
            val radius = barHeight / 2f

            val clipPath =
                Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = 0f,
                            top = 0f,
                            right = totalWidth,
                            bottom = barHeight,
                            cornerRadius = CornerRadius(radius),
                        ),
                    )
                }

            clipPath(clipPath) {
                drawRect(color = trackColor, topLeft = Offset(0f, 0f), size = Size(totalWidth, barHeight))

                if (stepCount != null && stepCount > 0) {
                    val fillWidth = (totalWidth * (count.toFloat() / barMax(stepGoal))).coerceAtMost(totalWidth)
                    drawRect(
                        color = fillColor,
                        topLeft = Offset(0f, 0f),
                        size = Size(fillWidth, barHeight),
                    )
                }
            }

            drawRoundRect(
                color = outlineColor,
                cornerRadius = CornerRadius(radius),
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        Spacer(Modifier.height(6.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (stepCount != null) "${count.formatSteps()} steps" else "-- steps",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (stepCount != null) fillColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (stepCount != null) status.name else "NO DATA",
                style = MaterialTheme.typography.labelSmall,
                color = if (stepCount != null) fillColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun Int.formatSteps(): String = NumberFormat.getNumberInstance().format(this)
