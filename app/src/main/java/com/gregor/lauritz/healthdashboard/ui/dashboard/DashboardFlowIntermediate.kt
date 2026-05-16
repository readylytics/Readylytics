package com.gregor.lauritz.healthdashboard.ui.dashboard

import androidx.compose.runtime.Immutable
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.preferences.CardConfigurationRepository
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardConfiguration
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardManagementDelegate
import com.gregor.lauritz.healthdashboard.domain.dashboard.DailySummaryRepository
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyResult
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId

/**
 * Basic inputs for dashboard (date-dependent data).
 * Extracted to separate testing of date-range queries.
 *
 * This data class represents the foundation layer - all the raw data
 * fetched from repositories based on the selected date.
 */
@Immutable
data class DashboardBasicInputs(
    val selectedDate: LocalDate,
    val summary: DailySummary?,
    val userPreferences: com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences,
    val circadianResult: CircadianConsistencyResult?,
    val paiSummaries: List<DailySummary>,
)

/**
 * Card configuration state.
 * Extracted to separate testing of card management.
 *
 * This data class represents the middle layer - card visibility,
 * ordering, and display configuration.
 */
@Immutable
data class DashboardCardState(
    val isManagingCards: Boolean,
    val cardConfiguration: List<CardConfiguration>,
    val lastSleepSession: SleepSessionEntity?,
)

/**
 * Real-time state (sync, foreground).
 * Extracted to separate testing of sync integration.
 *
 * This data class represents the realtime layer - transient state
 * that changes frequently during app usage.
 */
@Immutable
data class DashboardRealtimeState(
    val isSyncing: Boolean,
)

/**
 * Creates the basic inputs flow (date-dependent queries).
 * Can be tested independently of card management/sync state.
 *
 * This flow handles:
 * - Daily summary for selected date
 * - User preferences
 * - Circadian consistency data
 * - 7-day PAI breakdown
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun createDashboardBasicInputsFlow(
    selectedDate: Flow<LocalDate>,
    dailySummaryRepository: DailySummaryRepository,
    settingsRepository: SettingsRepository,
    circadianRepository: CircadianConsistencyRepository,
): Flow<DashboardBasicInputs> =
    selectedDate.flatMapLatest { date ->
        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)

        // Select appropriate summary flow based on whether date is today or historical
        val summaryFlow =
            if (date == today) {
                val todayMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
                dailySummaryRepository.observeSince(todayMs).map { it.firstOrNull() }
            } else {
                val midnightMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
                dailySummaryRepository.observeByDate(midnightMs)
            }

        // PAI breakdown is always 7-day window
        val paiFromMs = date.minusDays(6).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val paiBreakdownFlow = dailySummaryRepository.observeSince(paiFromMs)

        // Combine all basic inputs
        combine(
            summaryFlow,
            settingsRepository.userPreferences,
            circadianRepository.resultFor(date),
            paiBreakdownFlow,
        ) { summary, prefs, circadian, paiSummaries ->
            DashboardBasicInputs(
                selectedDate = date,
                summary = summary,
                userPreferences = prefs,
                circadianResult = circadian,
                paiSummaries = paiSummaries,
            )
        }
    }

/**
 * Creates the card state flow.
 * Can be tested independently of basic inputs/sync state.
 *
 * This flow handles:
 * - Card management mode (editing vs viewing)
 * - Card visibility and ordering configuration
 * - Current sleep session data
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun createDashboardCardStateFlow(
    selectedDate: Flow<LocalDate>,
    cardManagementDelegate: CardManagementDelegate,
    cardConfigRepository: CardConfigurationRepository,
    dailySummaryRepository: DailySummaryRepository,
): Flow<DashboardCardState> {
    val zoneId = ZoneId.systemDefault()

    return combine(
        cardManagementDelegate.isManagingCards,
        cardConfigRepository.dashboardCardConfigurations(),
        selectedDate.flatMapLatest { date ->
            // Get the most recent sleep session ending on the selected date
            dailySummaryRepository.observeFirstSessionEndingInRange(
                fromMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                toMs = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            )
        },
    ) { isManaging, cardConfig, session ->
        DashboardCardState(
            isManagingCards = isManaging,
            cardConfiguration = cardConfig,
            lastSleepSession = session,
        )
    }
}

/**
 * Creates the realtime state flow (sync, foreground).
 * Can be tested independently of other state.
 *
 * This flow handles:
 * - Sync progress and completion state
 * - Background work status
 */
fun createDashboardRealtimeStateFlow(
    foregroundSyncController: ForegroundSyncController,
): Flow<DashboardRealtimeState> =
    foregroundSyncController.isSyncing.map { isSyncing ->
        DashboardRealtimeState(isSyncing = isSyncing)
    }

/**
 * Combines all three dashboard input flows into a single aggregation.
 *
 * This is the single aggregation point where all three layers
 * (basic inputs, card state, realtime state) are combined.
 * Everything else is tested and validated separately.
 */
fun combineDashboardInputs(
    basicInputs: Flow<DashboardBasicInputs>,
    cardState: Flow<DashboardCardState>,
    realtimeState: Flow<DashboardRealtimeState>,
): Flow<DashboardCombinedInputs> =
    combine(basicInputs, cardState, realtimeState) { basic, card, realtime ->
        DashboardCombinedInputs(basic, card, realtime)
    }

/**
 * Container for all combined inputs before final transformation.
 * Used internally during flow composition.
 */
@Immutable
data class DashboardCombinedInputs(
    val basicInputs: DashboardBasicInputs,
    val cardState: DashboardCardState,
    val realtimeState: DashboardRealtimeState,
)
