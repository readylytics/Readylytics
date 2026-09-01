package app.readylytics.health.feature.sleep

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
import app.readylytics.health.core.model.domain.repository.HeartRateRecordData
import app.readylytics.health.core.model.domain.repository.HeartRateResolution
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import app.readylytics.health.core.ui.components.DayTimelineScale
import java.time.Instant
import java.time.format.DateTimeFormatter
import app.readylytics.health.core.ui.R as CoreUiR

// See the file-header comment in SleepHrChart.kt: this file holds the state holders and their
// @Composable factories that were extracted out of SleepHrChart to clear detekt's
// LongMethod/CyclomaticComplexMethod/TooManyFunctions thresholds without changing any behavior.

internal class SleepHrPulseAnimation(
    val radiusCoeff: State<Float>,
    val alpha: State<Float>,
)

@Composable
internal fun rememberSleepHrPulseAnimation(): SleepHrPulseAnimation {
    // Pulsing animation for the selected point, matching SleepStagesChart's halo directly above this chart
    val infiniteTransition = rememberInfiniteTransition(label = "sleepHrPulseTransition")
    val radiusCoeff =
        infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.6f,
            animationSpec =
                infiniteRepeatable(animation = tween(1200, easing = EaseInOutSine), repeatMode = RepeatMode.Reverse),
            label = "sleepHrPulseRadiusCoeff",
        )
    val alpha =
        infiniteTransition.animateFloat(
            initialValue = 0.15f,
            targetValue = 0.4f,
            animationSpec =
                infiniteRepeatable(animation = tween(1200, easing = EaseInOutSine), repeatMode = RepeatMode.Reverse),
            label = "sleepHrPulseAlpha",
        )
    return SleepHrPulseAnimation(radiusCoeff, alpha)
}

internal data class SleepHrChartStyle(
    val lineColor: Color,
    val axisLineColor: Color,
    val textMeasurer: TextMeasurer,
    val labelStyle: TextStyle,
    val axisTitleStyle: TextStyle,
    val timeFormatter: DateTimeFormatter,
    val bpmUnitLabel: String,
)

@Composable
internal fun rememberSleepHrChartStyle(timeFormatter: DateTimeFormatter): SleepHrChartStyle {
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    return SleepHrChartStyle(
        lineColor = MaterialTheme.colorScheme.primary,
        axisLineColor = MaterialTheme.colorScheme.outlineVariant,
        textMeasurer = rememberTextMeasurer(),
        labelStyle = TextStyle(color = axisTextColor, fontSize = 10.sp),
        axisTitleStyle = TextStyle(color = axisTextColor, fontSize = 12.sp),
        timeFormatter = timeFormatter,
        bpmUnitLabel = stringResource(CoreUiR.string.unit_bpm),
    )
}

internal data class SleepHrDerivedData(
    val sortedSamples: List<HeartRateRecordData>,
    val segments: List<List<HeartRateRecordData>>,
    val yMin: Int,
    val yMax: Int,
    val labelTimestamps: List<Long>,
    val yLabels: List<Int>,
)

@Composable
internal fun rememberSleepHrDerivedData(
    session: SleepSessionData,
    samples: List<HeartRateRecordData>,
): SleepHrDerivedData {
    val sortedSamples = remember(samples) { samples.sortedBy { it.timestampMs } }
    val yMin = remember(sortedSamples) { (sortedSamples.minOf { it.beatsPerMinute } - 10).coerceAtLeast(30) }
    val yMax =
        remember(sortedSamples, yMin) {
            (sortedSamples.maxOf { it.beatsPerMinute } + 10).coerceAtLeast(yMin + 20)
        }
    val segments =
        remember(sortedSamples) { SleepHrChartHelper.splitIntoSegments(sortedSamples, SLEEP_HR_GAP_THRESHOLD_MS) }
    val labelTimestamps =
        remember(session.startTime, session.endTime) { getLabelTimestamps(session.startTime, session.endTime) }
    val yLabels =
        remember(yMin, yMax) {
            (0 until SLEEP_HR_Y_TICK_COUNT).map { i -> yMin + (yMax - yMin) * i / (SLEEP_HR_Y_TICK_COUNT - 1) }
        }
    return SleepHrDerivedData(sortedSamples, segments, yMin, yMax, labelTimestamps, yLabels)
}

internal class SleepHrInteractionState(
    val scaleX: MutableFloatState,
    val offsetX: MutableFloatState,
    val selectedSample: MutableState<HeartRateRecordData?>,
)

@Composable
internal fun rememberSleepHrInteractionState(
    session: SleepSessionData,
    sortedSamples: List<HeartRateRecordData>,
): SleepHrInteractionState {
    val scaleX = remember { mutableFloatStateOf(1f) }
    val offsetX = remember { mutableFloatStateOf(0f) }
    val selectedSample = remember { mutableStateOf<HeartRateRecordData?>(null) }

    LaunchedEffect(session.id, sortedSamples) {
        if (selectedSample.value != null &&
            sortedSamples.none { it.timestampMs == selectedSample.value?.timestampMs }
        ) {
            selectedSample.value = null
        }
    }

    LaunchedEffect(session.id) {
        scaleX.floatValue = 1f
        offsetX.floatValue = 0f
    }

    return remember(scaleX, offsetX, selectedSample) { SleepHrInteractionState(scaleX, offsetX, selectedSample) }
}

internal class SleepHrChartState(
    val session: SleepSessionData,
    val data: SleepHrDerivedData,
    val scale: DayTimelineScale,
    val interaction: SleepHrInteractionState,
    val pulse: SleepHrPulseAnimation,
    val style: SleepHrChartStyle,
    val resolution: HeartRateResolution,
)

internal data class SleepHrAccessibility(
    val chartSummary: String,
    val selectedValueDescription: String,
    val customActions: List<CustomAccessibilityAction>,
)

@Composable
internal fun rememberSleepHrAccessibility(
    sortedSamples: List<HeartRateRecordData>,
    selectedSampleState: MutableState<HeartRateRecordData?>,
    timeFormatter: DateTimeFormatter,
): SleepHrAccessibility {
    var selectedSample by selectedSampleState
    val prevActionLabel = stringResource(CoreUiR.string.action_previous_point)
    val nextActionLabel = stringResource(CoreUiR.string.action_next_point)
    val clearActionLabel = stringResource(CoreUiR.string.action_clear_selection)

    val customActionsList =
        remember(selectedSample, sortedSamples) {
            val list = mutableListOf<CustomAccessibilityAction>()
            if (sortedSamples.isNotEmpty()) {
                list.add(
                    CustomAccessibilityAction(prevActionLabel) {
                        val currentIndex =
                            sortedSamples.indexOfFirst { it.timestampMs == selectedSample?.timestampMs }
                        selectedSample =
                            if (currentIndex > 0) sortedSamples[currentIndex - 1] else sortedSamples.last()
                        true
                    },
                )
                list.add(
                    CustomAccessibilityAction(nextActionLabel) {
                        val currentIndex =
                            sortedSamples.indexOfFirst { it.timestampMs == selectedSample?.timestampMs }
                        selectedSample =
                            if (currentIndex != -1 && currentIndex < sortedSamples.lastIndex) {
                                sortedSamples[currentIndex + 1]
                            } else {
                                sortedSamples.first()
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

    val chartSummary = stringResource(R.string.chart_accessibility_sleep_hr_summary)
    val selectedValueDescription =
        selectedSample?.let { sample ->
            val timeStr = timeFormatter.format(Instant.ofEpochMilli(sample.timestampMs))
            stringResource(R.string.chart_accessibility_selected_sleep_hr, sample.beatsPerMinute, timeStr)
        } ?: stringResource(CoreUiR.string.chart_accessibility_no_selection)

    return SleepHrAccessibility(chartSummary, selectedValueDescription, customActionsList)
}
