package app.readylytics.health.feature.dashboard

import androidx.compose.runtime.Immutable
import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.InsightType
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.model.domain.model.SleepSessionSummary
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.sync.RecalcProgress
import app.readylytics.health.core.scoring.domain.insights.InsightParams
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.core.ui.common.UiText
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.model.HeartRateDaySummary
import java.time.LocalDate

/** A single "copy today's prompt" request, made distinguishable by a monotonic [requestId]. */
data class PromptRequest(
    val text: String,
    val requestId: Int,
)

@Immutable
data class DashboardUiState(
    val summary: DailySummary? = null,
    val selectedDate: LocalDate = LocalDate.ofEpochDay(0),
    val today: LocalDate = LocalDate.ofEpochDay(0),
    val cardDataMap: Map<CardId, UniversalMetricPresentation> = emptyMap(),
    val circadianConsistency: CircadianConsistencyResult? = null,
    val restingHrCard: UniversalMetricPresentation? = null,
    val rasDailyBreakdown: List<Pair<String, Float>> = emptyList(),
    val stepCount: Int? = null,
    val stepGoal: Int = 10000,
    val lastSleepSession: SleepSessionSummary? = null,
    val cardConfigurations: List<CardConfiguration> = emptyList(),
    val isManagingCards: Boolean = false,
    val isRefreshing: Boolean = false,
    val recalcProgress: RecalcProgress? = null,
    val isComputingMetrics: Boolean = false,
    val isCalibrating: Boolean = false,
    val errorMessage: UiText? = null,
    val heartRateDaySummary: HeartRateDaySummary? = null,
    val activeInsightTypes: Set<InsightType> = emptySet(),
    val currentInsight: InsightType? = null,
    val currentInsightParams: InsightParams = InsightParams.None,
    val visibleInsightQueue: List<InsightType> = emptyList(),
    val dismissedInsightCount: Int = 0,
    val goalSleepHours: Float = 8f,
    val userPreferences: UserPreferences = UserPreferences(),
    val yesterdaySleepScoreRounded: Int? = null,
    val yesterdayReadiness: Float? = null,
)

/**
 * The exact subset of [DashboardUiState] that `buildCardDataMap` reads. Used as a single
 * `remember` key on the dashboard so the card map is rebuilt only when card-relevant content
 * changes — never on high-frequency sync ticks (isRefreshing/recalcProgress), and without the
 * per-recomposition `Any?[]` allocation a multi-key vararg `remember` would incur.
 *
 * Fields derivable from others are intentionally omitted: restingHrCard/stepCount/stepGoal/
 * goalSleepHours are covered by cardDataMap/summary/userPreferences. heartRateDaySummary,
 * circadianConsistency and the insight fields are kept because the cards read them directly and
 * they are NOT part of the ViewModel-computed cardDataMap.
 */
@Immutable
data class DashboardCardInputs(
    val cardDataMap: Map<CardId, UniversalMetricPresentation>,
    val summary: DailySummary?,
    val circadianConsistency: CircadianConsistencyResult?,
    val heartRateDaySummary: HeartRateDaySummary?,
    val selectedDate: LocalDate,
    val userPreferences: UserPreferences,
    val activeInsightTypes: Set<InsightType>,
    val currentInsight: InsightType?,
    val currentInsightParams: InsightParams,
    val dismissedInsightCount: Int,
    val yesterdaySleepScoreRounded: Int?,
    val yesterdayReadiness: Float?,
    val isManagingCards: Boolean,
    val isComputingMetrics: Boolean,
)

fun DashboardUiState.cardInputs(): DashboardCardInputs =
    DashboardCardInputs(
        cardDataMap = cardDataMap,
        summary = summary,
        circadianConsistency = circadianConsistency,
        heartRateDaySummary = heartRateDaySummary,
        selectedDate = selectedDate,
        userPreferences = userPreferences,
        activeInsightTypes = activeInsightTypes,
        currentInsight = currentInsight,
        currentInsightParams = currentInsightParams,
        dismissedInsightCount = dismissedInsightCount,
        yesterdaySleepScoreRounded = yesterdaySleepScoreRounded,
        yesterdayReadiness = yesterdayReadiness,
        isManagingCards = isManagingCards,
        isComputingMetrics = isComputingMetrics,
    )

@Immutable
data class CardData(
    val title: String,
    val value: String,
    val unit: String,
    val status: MetricStatus,
    val tooltip: String,
    val action: DashboardAction? = null,
    val secondaryText: String? = null,
)

enum class DashboardAction {
    NAVIGATE_SLEEP,
    NAVIGATE_WORKOUTS,
    NAVIGATE_RHR,
    NAVIGATE_STEPS,
    NAVIGATE_WEIGHT,
    NAVIGATE_BODY_FAT,
    NAVIGATE_BLOOD_PRESSURE,
    NAVIGATE_VITALS,
}
