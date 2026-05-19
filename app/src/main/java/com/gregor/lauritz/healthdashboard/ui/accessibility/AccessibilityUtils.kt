package com.gregor.lauritz.healthdashboard.ui.accessibility

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Accessibility enhancement utilities for ensuring all UI elements are properly labeled.
 * Required for screen reader compatibility and WCAG 2.1 AA compliance.
 *
 * Add semantic accessibility label to any Composable.
 *
 * @param label Human-readable description of the element
 * @return Modified with accessibility semantics
 */
fun Modifier.accessibilityLabel(label: String?): Modifier =
    if (label != null) {
        this.semantics { contentDescription = label }
    } else {
        this
    }

/**
 * Generate accessibility descriptions for common patterns.
 * All string templates are now parameterized for i18n support and flexibility.
 */
object AccessibilityLabels {
    fun scoreValue(
        label: String,
        value: Int?,
        unavailableText: String = "Data not available",
    ): String =
        if (value == null) {
            "$label: $unavailableText"
        } else {
            "$label score: $value out of 100"
        }

    fun metricValue(
        title: String,
        value: String?,
        unit: String?,
        unavailableText: String = "Data not available",
    ): String =
        if (value == null) {
            "$title: $unavailableText"
        } else if (unit != null) {
            "$title: $value $unit"
        } else {
            "$title: $value"
        }

    fun skeletonLoader(
        componentName: String,
        loadingTemplate: String = "Loading %s, please wait",
    ): String = loadingTemplate.replace("%s", componentName)

    fun loadingProgress(
        percentage: Int,
        progressTemplate: String = "Loading in progress, %d percent complete",
    ): String = progressTemplate.replace("%d", percentage.toString())

    fun dateNavigation(
        date: String,
        isToday: Boolean,
    ): String = if (isToday) "Today, $date" else date

    fun trendChart(
        chartName: String,
        direction: String?,
    ): String =
        if (direction != null) {
            "$chartName chart trending $direction"
        } else {
            "$chartName chart"
        }
}

/**
 * Accessibility status descriptions for different metric states.
 */
object AccessibilityStatus {
    fun metricStatus(
        title: String,
        status: String,
    ): String = "$title status: $status"

    fun alertMessage(
        severity: String,
        message: String,
    ): String = "$severity alert: $message"

    fun guidanceMessage(tip: String): String = "Tip: $tip"
}
