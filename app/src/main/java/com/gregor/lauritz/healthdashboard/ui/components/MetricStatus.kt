package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.gregor.lauritz.healthdashboard.ui.theme.LocalExtendedColors

enum class MetricStatus {
    CALIBRATING,
    OPTIMAL,
    WARNING,
    POOR,
}

@Composable
fun MetricStatus.containerColor(): Color =
    when (this) {
        MetricStatus.CALIBRATING -> MaterialTheme.colorScheme.surfaceVariant
        MetricStatus.OPTIMAL -> LocalExtendedColors.current.successContainer
        MetricStatus.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
        MetricStatus.POOR -> MaterialTheme.colorScheme.errorContainer
    }

@Composable
fun MetricStatus.onContainerColor(): Color =
    when (this) {
        MetricStatus.CALIBRATING -> MaterialTheme.colorScheme.onSurfaceVariant
        MetricStatus.OPTIMAL -> LocalExtendedColors.current.onSuccessContainer
        MetricStatus.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
        MetricStatus.POOR -> MaterialTheme.colorScheme.onErrorContainer
    }
