package com.gregor.lauritz.healthdashboard.ui.dashboard

import java.time.LocalDate

sealed interface DashboardEvent {
    data class DateSelected(
        val date: LocalDate,
    ) : DashboardEvent

    data object PreviousDay : DashboardEvent

    data object NextDay : DashboardEvent

    data object Refresh : DashboardEvent

    data object ToggleCardManagement : DashboardEvent
}
