package app.readylytics.health.ui.dashboard

import androidx.compose.runtime.Immutable
import app.readylytics.health.data.preferences.CardConfigurationRepository
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardManagementDelegate
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.InsightDismissalRepository
import app.readylytics.health.domain.repository.SleepSessionData
import app.readylytics.health.domain.scoring.CircadianConsistencyRepository
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.domain.sync.ForegroundSyncController
import app.readylytics.health.domain.sync.RecalcProgress
import app.readylytics.health.ui.heartrate.HeartRateDaySummary
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
    val userPreferences: app.readylytics.health.data.preferences.UserPreferences,
    val circadianResult: CircadianConsistencyResult?,
    val paiSummaries: List<DailySummary>,
    val dismissedInsightTypes: Set<InsightType> = emptySet(),
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
    val lastSleepSession: SleepSessionData?,
    val pendingConfiguration: List<CardConfiguration>?,
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
    val recalcProgress: RecalcProgress? = null,
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
    insightDismissalRepository: InsightDismissalRepository,
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
        val paiFromMs =
            date
                .minusDays(6)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        val paiBreakdownFlow = dailySummaryRepository.observeSince(paiFromMs)

        val dismissalFlow =
            insightDismissalRepository
                .observeForDate(date.atStartOfDay(zoneId).toInstant().toEpochMilli())

        // Combine all basic inputs
        combine(
            summaryFlow,
            settingsRepository.userPreferences,
            circadianRepository.resultFor(date),
            paiBreakdownFlow,
            dismissalFlow,
        ) { summary, prefs, circadian, paiSummaries, dismissed ->
            DashboardBasicInputs(
                selectedDate = date,
                summary = summary,
                userPreferences = prefs,
                circadianResult = circadian,
                paiSummaries = paiSummaries,
                dismissedInsightTypes = dismissed,
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
        cardManagementDelegate.pendingConfigs,
        cardConfigRepository.dashboardCardConfigurations(),
        selectedDate.flatMapLatest { date ->
            // Get the most recent sleep session ending on the selected date
            dailySummaryRepository.observeFirstSessionEndingInRange(
                fromMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                toMs =
                    date
                        .plusDays(1)
                        .atStartOfDay(zoneId)
                        .toInstant()
                        .toEpochMilli(),
            )
        },
    ) { isManaging, pendingConfig, cardConfig, session ->
        DashboardCardState(
            isManagingCards = isManaging,
            cardConfiguration = cardConfig,
            lastSleepSession = session,
            pendingConfiguration = pendingConfig,
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
    combine(
        foregroundSyncController.isSyncing,
        foregroundSyncController.recalcProgress,
    ) { isSyncing, recalcProgress ->
        DashboardRealtimeState(isSyncing = isSyncing, recalcProgress = recalcProgress)
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

@OptIn(ExperimentalCoroutinesApi::class)
fun createDashboardHrFlow(
    selectedDate: Flow<LocalDate>,
    heartRateRepository: HeartRateRepository,
): Flow<HeartRateDaySummary?> =
    selectedDate.flatMapLatest { date ->
        val zoneId = ZoneId.systemDefault()
        val startMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMs =
            date
                .plusDays(1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        heartRateRepository.observeByTimeRange(startMs, endMs).map { entities ->
            if (entities.isEmpty()) return@map null
            // entities already sorted ASC by the DAO query; single pass for stats
            var minBpm = Int.MAX_VALUE
            var maxBpm = Int.MIN_VALUE
            var sumBpm = 0
            for (entity in entities) {
                val bpm = entity.beatsPerMinute
                if (bpm < minBpm) minBpm = bpm
                if (bpm > maxBpm) maxBpm = bpm
                sumBpm += bpm
            }
            val hourlyMap =
                entities.groupBy { entity ->
                    ((entity.timestampMs - startMs) / 60_000L).toInt() / 60
                }
            val hourly =
                (0..23).mapNotNull { hour ->
                    hourlyMap[hour]?.let { group ->
                        hour to group.sumOf { it.beatsPerMinute } / group.size
                    }
                }
            HeartRateDaySummary(
                minBpm = minBpm,
                maxBpm = maxBpm,
                avgBpm = sumBpm / entities.size,
                hourlySamples = hourly,
            )
        }
    }
