package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.data.preferences.AppTheme
import com.gregor.lauritz.healthdashboard.ui.theme.FitDashboardTheme
import com.gregor.lauritz.healthdashboard.ui.theme.LocalExtendedColors

enum class MetricStatus {
    CALIBRATING,
    OPTIMAL,
    NEUTRAL,
    WARNING,
    POOR,
}

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
