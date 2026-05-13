package com.gregor.lauritz.healthdashboard.ui.workouts

import androidx.compose.runtime.Composable
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId

fun buildWorkoutsCardDataMap(uiState: WorkoutsUiState): Map<CardId, @Composable () -> Unit> {
    val cardMap = mutableMapOf<CardId, @Composable () -> Unit>()

    // Add workouts-specific cards here based on actual Workouts screen implementation
    // For now, these are placeholders - you'll customize based on your actual cards

    return cardMap.filterValues { true }
}
