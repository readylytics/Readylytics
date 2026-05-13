package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus

@Composable
fun MetricStatus.containerColor(): Color =
    when (this) {
        MetricStatus.CALIBRATING -> MaterialTheme.colorScheme.surfaceVariant
        MetricStatus.OPTIMAL -> MaterialTheme.colorScheme.primaryContainer
        MetricStatus.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHighest
        MetricStatus.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
        MetricStatus.POOR -> MaterialTheme.colorScheme.errorContainer
    }

@Composable
fun MetricStatus.onContainerColor(): Color =
    when (this) {
        MetricStatus.CALIBRATING -> MaterialTheme.colorScheme.onSurfaceVariant
        MetricStatus.OPTIMAL -> MaterialTheme.colorScheme.onPrimaryContainer
        MetricStatus.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        MetricStatus.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
        MetricStatus.POOR -> MaterialTheme.colorScheme.onErrorContainer
    }

@Composable
fun MetricStatus.gaugeColor(): Color =
    when (this) {
        MetricStatus.NEUTRAL -> MaterialTheme.colorScheme.outline
        MetricStatus.WARNING -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
        else -> this.onContainerColor()
    }

@Composable
fun MetricStatus.contentColor(): Color =
    when (this) {
        MetricStatus.CALIBRATING -> MaterialTheme.colorScheme.onSurfaceVariant
        MetricStatus.OPTIMAL -> MaterialTheme.colorScheme.primary
        MetricStatus.NEUTRAL -> MaterialTheme.colorScheme.outline
        MetricStatus.WARNING -> MaterialTheme.colorScheme.tertiary
        MetricStatus.POOR -> MaterialTheme.colorScheme.error
    }
