package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.model.strainRatioStatus
import com.gregor.lauritz.healthdashboard.ui.theme.LocalExtendedColors

@Composable
fun MetricStatus.containerColor(): Color =
    when (this) {
        MetricStatus.NO_DATA -> MaterialTheme.colorScheme.surfaceVariant
        MetricStatus.CALIBRATING -> MaterialTheme.colorScheme.surfaceVariant
        MetricStatus.OPTIMAL -> MaterialTheme.colorScheme.primaryContainer
        MetricStatus.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHighest
        MetricStatus.WARNING -> LocalExtendedColors.current.warningContainer
        MetricStatus.POOR -> MaterialTheme.colorScheme.errorContainer
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
fun Float?.strainRatioGaugeColor(): Color =
    this?.strainRatioStatus()?.gaugeColor() ?: MetricStatus.CALIBRATING.gaugeColor()
