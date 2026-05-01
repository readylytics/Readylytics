package com.gregor.lauritz.healthdashboard.ui.sleep

import androidx.compose.runtime.Composable
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId

fun buildSleepCardDataMap(
    uiState: SleepUiState,
): Map<CardId, @Composable () -> Unit> {
    val cardMap = mutableMapOf<CardId, @Composable () -> Unit>()

    // Add sleep-specific cards here based on actual Sleep screen implementation
    // For now, these are placeholders - you'll customize based on your actual cards

    return cardMap.filterValues { it != null }
}
