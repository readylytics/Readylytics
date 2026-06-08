package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.gregor.lauritz.healthdashboard.ui.common.ChartUtils
import java.text.NumberFormat
import java.time.LocalDate

// stepGoal fills bar to 75% width — mirrors PAI bar design
private fun barMax(stepGoal: Int): Float = stepGoal / 0.75f

@Composable
fun StepsBar(
    stepCount: Int?,
    stepGoal: Int,
    modifier: Modifier = Modifier,
    dateForTooltip: LocalDate? = null,
) {
    var tooltipState by remember { mutableStateOf<DataPointTooltipData?>(null) }
    var activeTapOffset by remember { mutableStateOf<Offset?>(null) }

    // Breathing halo animation on selection
    val infiniteTransition = rememberInfiniteTransition(label = "stepsHaloTransition")
    val haloRadiusCoeff by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.6f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1200, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "stepsHaloRadiusCoeff",
    )
    val haloAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.4f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1200, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "stepsHaloAlpha",
    )

    val count = stepCount ?: 0
    val status = if (stepCount != null) stepsStatus(count, stepGoal) else MetricStatus.CALIBRATING
    val fillColor = status.gaugeColor()
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val primaryColor = MaterialTheme.colorScheme.primary

    Column(modifier = modifier) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Canvas(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .detectCanvasTap(
                            segments =
                                remember {
                                    listOf(
                                        SegmentHitBox(
                                            index = 0,
                                            xStart = 0f,
                                            xEnd = 1f,
                                            label = "Steps",
                                        ),
                                    )
                                },
                            onSegmentTapped = { _, _, tapOffset ->
                                if (stepCount != null && dateForTooltip != null) {
                                    activeTapOffset = tapOffset
                                    val dateString = ChartUtils.formatTooltipDate(dateForTooltip)
                                    val valueText = "$stepCount"
                                    val dateText = dateString
                                    tooltipState =
                                        DataPointTooltipData(
                                            valueText = valueText,
                                            dateText = dateText,
                                            offset =
                                                androidx.compose.ui.unit.IntOffset(
                                                    x = tapOffset.x.toInt(),
                                                    y = tapOffset.y.toInt(),
                                                ),
                                        )
                                }
                            },
                        ),
            ) {
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

                // Draw highlight overlay and indicator line
                if (activeTapOffset != null) {
                    val tapX = activeTapOffset!!.x.coerceIn(0f, totalWidth)

                    // Vertical indicator line through the bar
                    drawLine(
                        color = primaryColor.copy(alpha = 0.4f),
                        start = Offset(tapX, 0f),
                        end = Offset(tapX, barHeight),
                        strokeWidth = 2.dp.toPx(),
                    )

                    // Concentric highlight circles with breathing pulsing animation
                    drawCircle(
                        color = primaryColor.copy(alpha = haloAlpha),
                        center = Offset(tapX, barHeight / 2f),
                        radius = (8.dp.toPx() * haloRadiusCoeff),
                    )
                    drawCircle(
                        color = primaryColor,
                        center = Offset(tapX, barHeight / 2f),
                        radius = 4.dp.toPx(),
                    )
                }
            }

            if (tooltipState != null) {
                DataPointTooltip(
                    isVisible = true,
                    data = tooltipState!!,
                    onDismissRequest = {
                        tooltipState = null
                        activeTapOffset = null
                    },
                )
            }
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
