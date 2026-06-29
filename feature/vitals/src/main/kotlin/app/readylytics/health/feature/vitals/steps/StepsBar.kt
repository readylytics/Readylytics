package app.readylytics.health.feature.vitals.steps

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.ui.common.ChartUtils
import app.readylytics.health.core.ui.components.DataPointTooltip
import app.readylytics.health.core.ui.components.DataPointTooltipData
import app.readylytics.health.core.ui.components.HealthProgressBar
import app.readylytics.health.core.ui.components.ProgressBarSegment
import app.readylytics.health.core.ui.components.SegmentHitBox
import app.readylytics.health.core.ui.components.detectCanvasTap
import app.readylytics.health.core.ui.components.gaugeColor
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.stepsStatus
import app.readylytics.health.feature.vitals.R
import java.text.NumberFormat
import java.time.LocalDate
import app.readylytics.health.core.ui.R as CoreUiR

// stepGoal fills bar to 75% width — mirrors RAS bar design
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

    val prevActionLabel = stringResource(CoreUiR.string.action_previous_point)
    val nextActionLabel = stringResource(CoreUiR.string.action_next_point)
    val clearActionLabel = stringResource(CoreUiR.string.action_clear_selection)

    val formattedCount =
        stepCount?.let {
            java.text.NumberFormat
                .getNumberInstance()
                .format(it)
        } ?: "--"
    val formattedGoal =
        java.text.NumberFormat
            .getNumberInstance()
            .format(stepGoal)

    val selectedValueDescription =
        if (activeTapOffset != null) {
            stringResource(CoreUiR.string.chart_accessibility_selected_steps, formattedCount, formattedGoal)
        } else {
            stringResource(CoreUiR.string.chart_accessibility_no_selection)
        }

    val customActionsList =
        remember(activeTapOffset, stepCount) {
            val list = mutableListOf<CustomAccessibilityAction>()
            if (stepCount != null) {
                list.add(
                    CustomAccessibilityAction(prevActionLabel) {
                        if (activeTapOffset == null) {
                            activeTapOffset = Offset(0f, 0f)
                            val dateString = dateForTooltip?.let { ChartUtils.formatTooltipDate(it) } ?: ""
                            tooltipState =
                                DataPointTooltipData(
                                    valueText = "$stepCount",
                                    dateText = dateString,
                                    offset =
                                        androidx.compose.ui.unit
                                            .IntOffset(0, 0),
                                )
                        }
                        true
                    },
                )
                list.add(
                    CustomAccessibilityAction(nextActionLabel) {
                        if (activeTapOffset == null) {
                            activeTapOffset = Offset(0f, 0f)
                            val dateString = dateForTooltip?.let { ChartUtils.formatTooltipDate(it) } ?: ""
                            tooltipState =
                                DataPointTooltipData(
                                    valueText = "$stepCount",
                                    dateText = dateString,
                                    offset =
                                        androidx.compose.ui.unit
                                            .IntOffset(0, 0),
                                )
                        }
                        true
                    },
                )
            }
            if (activeTapOffset != null) {
                list.add(
                    CustomAccessibilityAction(clearActionLabel) {
                        activeTapOffset = null
                        tooltipState = null
                        true
                    },
                )
            }
            list
        }

    val chartSummary = stringResource(CoreUiR.string.chart_accessibility_steps_summary)

    Column(modifier = modifier) {
        Box(modifier = Modifier.fillMaxWidth()) {
            HealthProgressBar(
                segments =
                    if (stepCount != null && stepCount > 0) {
                        listOf(ProgressBarSegment(value = count.toFloat(), color = fillColor))
                    } else {
                        emptyList()
                    },
                max = barMax(stepGoal),
                height = 28.dp,
                trackColor = trackColor,
                outlineColor = outlineColor,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("StepsBarCanvas")
                        .semantics {
                            contentDescription = chartSummary
                            stateDescription = selectedValueDescription
                            customActions = customActionsList
                        }.detectCanvasTap(
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
                onDrawOverlay = { totalWidth, barHeight ->
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
                },
            )

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
            Spacer(Modifier.weight(1f))
            val formattedCount =
                stepCount?.let {
                    java.text.NumberFormat
                        .getNumberInstance()
                        .format(it)
                } ?: "--"
            val formattedGoal =
                java.text.NumberFormat
                    .getNumberInstance()
                    .format(stepGoal)
            Text(
                text = stringResource(R.string.steps_fraction_display, formattedCount, formattedGoal),
                style = MaterialTheme.typography.labelSmall,
                color = if (stepCount != null) fillColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun Int.formatSteps(): String = NumberFormat.getNumberInstance().format(this)
