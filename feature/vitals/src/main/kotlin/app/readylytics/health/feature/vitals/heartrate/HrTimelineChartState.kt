package app.readylytics.health.feature.vitals.heartrate

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import app.readylytics.health.core.model.domain.repository.HeartRateResolution
import app.readylytics.health.core.ui.components.DayTimelineScale
import app.readylytics.health.core.ui.components.HrZoneColors
import app.readylytics.health.core.ui.components.hrZoneColors
import app.readylytics.health.core.ui.model.HrSample
import java.time.Instant
import java.time.ZoneId
import app.readylytics.health.core.ui.R as CoreUiR

// See the file-header comment in HrTimelineChart.kt: this file holds the state holders and their
// @Composable factories that were extracted out of HrTimelineChartContent to clear detekt's
// LongMethod/CyclomaticComplexMethod/TooManyFunctions/LongParameterList thresholds without
// changing any behavior.

internal class HrTimelinePulseAnimation(
    val radiusCoeff: State<Float>,
    val alpha: State<Float>,
)

@Composable
internal fun rememberHrTimelinePulseAnimation(): HrTimelinePulseAnimation {
    // Pulsing animation for selected point highlight
    val infiniteTransition = rememberInfiniteTransition(label = "hrPulseTransition")
    val radiusCoeff =
        infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.6f,
            animationSpec =
                infiniteRepeatable(animation = tween(1200, easing = EaseInOutSine), repeatMode = RepeatMode.Reverse),
            label = "hrPulseRadiusCoeff",
        )
    val alpha =
        infiniteTransition.animateFloat(
            initialValue = 0.15f,
            targetValue = 0.4f,
            animationSpec =
                infiniteRepeatable(animation = tween(1200, easing = EaseInOutSine), repeatMode = RepeatMode.Reverse),
            label = "hrPulseAlpha",
        )
    return HrTimelinePulseAnimation(radiusCoeff, alpha)
}

internal data class HrTimelineChartStyle(
    val zoneColors: HrZoneColors,
    val lineColor: Color,
    val axisLineColor: Color,
    val textMeasurer: TextMeasurer,
    val labelStyle: TextStyle,
)

@Composable
internal fun rememberHrTimelineChartStyle(): HrTimelineChartStyle =
    HrTimelineChartStyle(
        zoneColors = hrZoneColors(),
        lineColor = MaterialTheme.colorScheme.primary,
        axisLineColor = MaterialTheme.colorScheme.outlineVariant,
        textMeasurer = rememberTextMeasurer(),
        labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp),
    )

internal data class HrTimelineDerivedData(
    val samples: List<HrSample>,
    val segments: List<List<HrSample>>,
    val yMin: Int,
    val yMax: Int,
    val hourLabels: List<Pair<Long, String>>,
    val yLabels: List<Int>,
)

@Composable
internal fun rememberHrTimelineDerivedData(
    samples: List<HrSample>,
    dayStartMs: Long,
    dayEndMs: Long,
    zoneBounds: HrZoneBounds,
    zoneId: ZoneId,
): HrTimelineDerivedData {
    val yMin =
        remember(samples, zoneBounds.zone1MinBpm) {
            (minOf(samples.minOf { it.bpm }, zoneBounds.zone1MinBpm) - 10).coerceAtLeast(30)
        }
    val yMax =
        remember(samples, zoneBounds.zone4MaxBpm) {
            maxOf(samples.maxOf { it.bpm }, zoneBounds.zone4MaxBpm) + 10
        }
    val segments =
        remember(samples) { HrChartHelper.splitIntoSegments(samples, HR_TIMELINE_GAP_THRESHOLD_MS) }
    val hourLabels =
        remember(dayStartMs, dayEndMs, zoneId) {
            HrChartHelper.generateHourLabels(dayStartMs, dayEndMs, zoneId)
        }
    val yLabels =
        remember(zoneBounds) {
            listOf(
                zoneBounds.zone1MinBpm,
                zoneBounds.zone1MaxBpm,
                zoneBounds.zone2MaxBpm,
                zoneBounds.zone3MaxBpm,
                zoneBounds.zone4MaxBpm,
            )
        }
    return HrTimelineDerivedData(samples, segments, yMin, yMax, hourLabels, yLabels)
}

internal class HrTimelineInteractionState(
    val scaleX: MutableFloatState,
    val offsetX: MutableFloatState,
    val selectedSample: MutableState<HrSample?>,
)

@Composable
internal fun rememberHrTimelineInteractionState(
    dayStartMs: Long,
    samples: List<HrSample>,
): HrTimelineInteractionState {
    val scaleX = remember { mutableFloatStateOf(1f) }
    val offsetX = remember { mutableFloatStateOf(0f) }
    val selectedSample = remember { mutableStateOf<HrSample?>(null) }

    // Clear selected sample on date/data changes
    LaunchedEffect(dayStartMs, samples) {
        if (selectedSample.value != null && samples.none { it.timeMs == selectedSample.value?.timeMs }) {
            selectedSample.value = null
        }
    }

    // Reset zoom/pan on date change
    LaunchedEffect(dayStartMs) {
        scaleX.floatValue = 1f
        offsetX.floatValue = 0f
    }

    return remember(scaleX, offsetX, selectedSample) { HrTimelineInteractionState(scaleX, offsetX, selectedSample) }
}

internal class HrTimelineChartState(
    val dayStartMs: Long,
    val zoneId: ZoneId,
    val zoneBounds: HrZoneBounds,
    val data: HrTimelineDerivedData,
    val scale: DayTimelineScale,
    val interaction: HrTimelineInteractionState,
    val pulse: HrTimelinePulseAnimation,
    val style: HrTimelineChartStyle,
    val resolution: HeartRateResolution,
)

internal data class HrTimelineAccessibility(
    val chartSummary: String,
    val selectedValueDescription: String,
    val customActions: List<CustomAccessibilityAction>,
)

@Composable
internal fun rememberHrTimelineAccessibility(
    samples: List<HrSample>,
    selectedSampleState: MutableState<HrSample?>,
    zoneId: ZoneId,
): HrTimelineAccessibility {
    var selectedSample by selectedSampleState
    val prevActionLabel = stringResource(CoreUiR.string.action_previous_point)
    val nextActionLabel = stringResource(CoreUiR.string.action_next_point)
    val clearActionLabel = stringResource(CoreUiR.string.action_clear_selection)

    val customActionsList =
        remember(selectedSample, samples) {
            val list = mutableListOf<CustomAccessibilityAction>()
            if (samples.isNotEmpty()) {
                list.add(
                    CustomAccessibilityAction(prevActionLabel) {
                        val currentIndex = samples.indexOfFirst { it.timeMs == selectedSample?.timeMs }
                        selectedSample =
                            if (currentIndex > 0) {
                                samples[currentIndex - 1]
                            } else {
                                samples.last()
                            }
                        true
                    },
                )
                list.add(
                    CustomAccessibilityAction(nextActionLabel) {
                        val currentIndex = samples.indexOfFirst { it.timeMs == selectedSample?.timeMs }
                        selectedSample =
                            if (currentIndex != -1 && currentIndex < samples.lastIndex) {
                                samples[currentIndex + 1]
                            } else {
                                samples.first()
                            }
                        true
                    },
                )
            }
            if (selectedSample != null) {
                list.add(
                    CustomAccessibilityAction(clearActionLabel) {
                        selectedSample = null
                        true
                    },
                )
            }
            list
        }

    val chartSummary = stringResource(CoreUiR.string.chart_accessibility_rhr_summary)
    val selectedValueDescription =
        selectedSample?.let { sample ->
            val timeStr = Instant.ofEpochMilli(sample.timeMs).atZone(zoneId).format(HR_TIMELINE_HOUR_FORMATTER)
            stringResource(CoreUiR.string.chart_accessibility_selected_rhr, sample.bpm, timeStr)
        } ?: stringResource(CoreUiR.string.chart_accessibility_no_selection)

    return HrTimelineAccessibility(chartSummary, selectedValueDescription, customActionsList)
}
