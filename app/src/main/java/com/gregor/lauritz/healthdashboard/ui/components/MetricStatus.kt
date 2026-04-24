package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.ui.theme.LocalExtendedColors

@Composable
fun MetricStatus.containerColor(): Color =
    when (this) {
        MetricStatus.CALIBRATING -> MaterialTheme.colorScheme.surfaceVariant
        MetricStatus.OPTIMAL -> LocalExtendedColors.current.successContainer
        MetricStatus.NEUTRAL -> MaterialTheme.colorScheme.tertiaryContainer
        MetricStatus.WARNING -> LocalExtendedColors.current.warningContainer
        MetricStatus.POOR -> MaterialTheme.colorScheme.errorContainer
    }

@Composable
fun MetricStatus.onContainerColor(): Color =
    when (this) {
        MetricStatus.CALIBRATING -> MaterialTheme.colorScheme.onSurfaceVariant
        MetricStatus.OPTIMAL -> LocalExtendedColors.current.onSuccessContainer
        MetricStatus.NEUTRAL -> MaterialTheme.colorScheme.onTertiaryContainer
        MetricStatus.WARNING -> LocalExtendedColors.current.onWarningContainer
        MetricStatus.POOR -> MaterialTheme.colorScheme.onErrorContainer
    }
