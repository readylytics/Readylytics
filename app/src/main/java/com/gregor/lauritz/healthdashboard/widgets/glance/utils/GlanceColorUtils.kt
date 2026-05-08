package com.gregor.lauritz.healthdashboard.widgets.glance.utils

import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceContext
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.ui.theme.FitDashboardTheme

/**
 * Glance-compatible color utilities for widgets.
 * Maps MetricStatus to Material 3 semantic colors consistent with dashboard.
 */
object GlanceColorUtils {
    /**
     * Get container color based on metric status.
     * Reuses dashboard color semantics.
     */
    fun containerColor(status: MetricStatus): Color = when (status) {
        MetricStatus.CALIBRATING -> Color(0xFF2A2D31) // surfaceVariant
        MetricStatus.OPTIMAL -> Color(0xFF1B5E20) // primaryContainer green
        MetricStatus.NEUTRAL -> Color(0xFFF5F5F5) // surfaceContainerHighest
        MetricStatus.WARNING -> Color(0xFFFFB74D) // tertiaryContainer orange
        MetricStatus.POOR -> Color(0xFFFFCDD2) // errorContainer red
    }

    /**
     * Get text color for content on status container.
     */
    fun onContainerColor(status: MetricStatus): Color = when (status) {
        MetricStatus.CALIBRATING -> Color(0xFFB0B0B0) // onSurfaceVariant
        MetricStatus.OPTIMAL -> Color(0xFFFFFFFF) // white on green
        MetricStatus.NEUTRAL -> Color(0xFF333333) // dark on light
        MetricStatus.WARNING -> Color(0xFF333333) // dark on orange
        MetricStatus.POOR -> Color(0xFF333333) // dark on red
    }

    /**
     * Get gauge/progress color (for bars and icons).
     */
    fun gaugeColor(status: MetricStatus): Color = when (status) {
        MetricStatus.CALIBRATING -> Color(0xFF999999) // outline @ 70% alpha
        MetricStatus.OPTIMAL -> Color(0xFF1B5E20) // primary green
        MetricStatus.NEUTRAL -> Color(0xFF666666) // outline
        MetricStatus.WARNING -> Color(0xFFFFB74D) // tertiary orange
        MetricStatus.POOR -> Color(0xFFD32F2F) // error red
    }
}
