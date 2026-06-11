package com.gregor.lauritz.healthdashboard.ui.dashboard

import com.gregor.lauritz.healthdashboard.domain.model.InsightType
import java.time.LocalDate

sealed interface DashboardEvent {
    data class DateSelected(
        val date: LocalDate,
    ) : DashboardEvent

    data object PreviousDay : DashboardEvent

    data object NextDay : DashboardEvent

    data object Refresh : DashboardEvent

    data object ToggleCardManagement : DashboardEvent

    data class DismissInsight(
        val type: InsightType,
    ) : DashboardEvent

    data object RestoreInsights : DashboardEvent
}
