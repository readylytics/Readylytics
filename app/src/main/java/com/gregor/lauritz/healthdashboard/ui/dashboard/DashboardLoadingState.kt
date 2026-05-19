package com.gregor.lauritz.healthdashboard.ui.dashboard

/**
 * Represents the dashboard's data loading state.
 * More semantically accurate than a simple Boolean flag.
 */
sealed interface DashboardLoadingState {
    /**
     * No sync operation in progress; data is stable or unavailable.
     */
    data object Idle : DashboardLoadingState

    /**
     * Sync operation in progress; metrics may be computing.
     * UI should display skeleton loaders.
     */
    data object SyncingMetrics : DashboardLoadingState

    /**
     * Metrics have been computed and saved; data is ready for display.
     * UI should display actual card content.
     */
    data object MetricsReady : DashboardLoadingState

    /**
     * An error occurred during sync; fallback to last-known data if available.
     */
    data class Error(
        val message: String,
    ) : DashboardLoadingState
}

/**
 * Contract: The loading state follows this transition:
 * Idle → SyncingMetrics → MetricsReady → Idle
 *
 * Error can occur from any state and returns to Idle after user action.
 */
fun DashboardLoadingState.shouldShowSkeleton(): Boolean =
    when (this) {
        DashboardLoadingState.Idle -> false
        DashboardLoadingState.SyncingMetrics -> true
        DashboardLoadingState.MetricsReady -> false
        is DashboardLoadingState.Error -> false
    }

fun DashboardLoadingState.isBusy(): Boolean =
    when (this) {
        DashboardLoadingState.Idle -> false
        DashboardLoadingState.SyncingMetrics -> true
        DashboardLoadingState.MetricsReady -> false
        is DashboardLoadingState.Error -> false
    }
