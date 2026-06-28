package app.readylytics.health.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.strainRatioStatus
import app.readylytics.health.core.designsystem.LocalExtendedColors

internal enum class MetricContainerTone {
    DEFAULT_CARD,
    NEUTRAL,
    PRIMARY,
    WARNING,
    ERROR,
}

internal fun MetricStatus.containerTone(): MetricContainerTone =
    when (this) {
        MetricStatus.NO_DATA -> MetricContainerTone.DEFAULT_CARD
        MetricStatus.CALIBRATING -> MetricContainerTone.DEFAULT_CARD
        MetricStatus.OPTIMAL -> MetricContainerTone.PRIMARY
        MetricStatus.NEUTRAL -> MetricContainerTone.NEUTRAL
        MetricStatus.WARNING -> MetricContainerTone.WARNING
        MetricStatus.POOR -> MetricContainerTone.ERROR
    }

@Composable
fun MetricStatus.containerColor(): Color =
    when (containerTone()) {
        MetricContainerTone.DEFAULT_CARD -> MaterialTheme.colorScheme.surfaceContainerLow
        MetricContainerTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHighest
        MetricContainerTone.PRIMARY -> MaterialTheme.colorScheme.primaryContainer
        MetricContainerTone.WARNING -> LocalExtendedColors.current.warningContainer
        MetricContainerTone.ERROR -> MaterialTheme.colorScheme.errorContainer
    }

@Composable
fun MetricStatus.gaugeColor(): Color =
    when (this) {
        MetricStatus.NEUTRAL -> MaterialTheme.colorScheme.outline
        MetricStatus.WARNING -> LocalExtendedColors.current.warning
        MetricStatus.POOR -> MaterialTheme.colorScheme.error
        else -> this.onContainerColor()
    }

@Composable
fun MetricStatus.onContainerColor(): Color =
    when (this) {
        MetricStatus.NO_DATA -> MaterialTheme.colorScheme.onSurfaceVariant
        MetricStatus.CALIBRATING -> MaterialTheme.colorScheme.onSurfaceVariant
        MetricStatus.OPTIMAL -> MaterialTheme.colorScheme.onPrimaryContainer
        MetricStatus.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        MetricStatus.WARNING -> LocalExtendedColors.current.onWarningContainer
        MetricStatus.POOR -> MaterialTheme.colorScheme.onErrorContainer
    }

@Composable
fun MetricStatus.contentColor(): Color =
    when (this) {
        MetricStatus.NO_DATA -> MaterialTheme.colorScheme.onSurfaceVariant
        MetricStatus.CALIBRATING -> MaterialTheme.colorScheme.onSurfaceVariant
        MetricStatus.OPTIMAL -> MaterialTheme.colorScheme.primary
        MetricStatus.NEUTRAL -> MaterialTheme.colorScheme.outline
        MetricStatus.WARNING -> LocalExtendedColors.current.warning
        MetricStatus.POOR -> MaterialTheme.colorScheme.errorContainer
    }

@Composable
fun Float?.strainRatioGaugeColor(): Color {
    val status = this?.strainRatioStatus() ?: MetricStatus.CALIBRATING
    return status.gaugeColor()
}
