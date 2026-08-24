package app.readylytics.health.ui.scaffold

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import app.readylytics.health.core.model.domain.model.InsightType
import app.readylytics.health.core.scoring.domain.insights.InsightParams
import app.readylytics.health.core.scoring.domain.insights.detail.DailyInsightContext
import app.readylytics.health.feature.dashboard.DashboardRoute
import app.readylytics.health.feature.dashboard.DashboardUiState
import app.readylytics.health.feature.dashboard.InsightCard
import app.readylytics.health.feature.dashboard.InsightRerunCard
import app.readylytics.health.feature.dashboard.getInsightIcon
import app.readylytics.health.feature.dashboard.toDailyInsightContext
import app.readylytics.health.feature.insights.InsightDetailRepository
import app.readylytics.health.feature.insights.InsightDetailSheet
import app.readylytics.health.ui.navigation.AppDestination
import app.readylytics.health.ui.navigation.TabDestination
import app.readylytics.health.core.ui.R as CoreUiR

internal fun NavGraphBuilder.dashboardDestinations(navController: NavHostController) {
    composable<TabDestination.Dashboard> {
        DashboardDestination(navController)
    }
}

@Composable
private fun DashboardDestination(navController: NavHostController) {
    var selectedInsightForDetails by remember { mutableStateOf<InsightType?>(null) }
    var selectedInsightParams by remember { mutableStateOf<InsightParams>(InsightParams.None) }
    var selectedInsightContext by remember { mutableStateOf<DailyInsightContext?>(null) }
    DashboardRoute(
        onNavigateToSleep = { navigateToTab(navController, TabDestination.Sleep) },
        onNavigateToWorkouts = { navigateToTab(navController, TabDestination.Workouts) },
        onNavigateToRhr = { navigateToTab(navController, TabDestination.Vitals) },
        onNavigateToSteps = { navController.navigate(AppDestination.StepDetail) },
        onNavigateToHeartRate = { navController.navigate(AppDestination.HeartRateDetail) },
        onNavigateToHrv = { navigateToTab(navController, TabDestination.Vitals) },
        onNavigateToWeight = { navController.navigate(AppDestination.WeightDetail) },
        onNavigateToBodyFat = { navController.navigate(AppDestination.BodyFatDetail) },
        onNavigateToBloodPressure = { navController.navigate(AppDestination.BloodPressureDetail) },
        onNavigateToVitals = { navigateToTab(navController, TabDestination.Vitals) },
        onOpenInsight = { selectedInsightParams = it },
        insightDetail = {
            val selected = selectedInsightForDetails
            val detailContext = selectedInsightContext
            if (selected != null && detailContext != null) {
                val context = LocalContext.current
                val detailRepository = remember(context) { InsightDetailRepository(context) }
                InsightDetailSheet(
                    content = detailRepository.getDetail(selected, detailContext, selectedInsightParams),
                    onDismiss = {
                        selectedInsightForDetails = null
                        selectedInsightParams = InsightParams.None
                        selectedInsightContext = null
                    },
                )
            }
        },
        insightsCard = { uiState, isEditing, onDismissInsight, onRestoreInsights, onOpenInsight ->
            DashboardInsightCardContent(
                uiState = uiState,
                isEditing = isEditing,
                onDismissInsight = onDismissInsight,
                onRestoreInsights = onRestoreInsights,
                onSelectInsight = { insight, context ->
                    selectedInsightForDetails = insight
                    selectedInsightContext = context
                    onOpenInsight(uiState.currentInsightParams)
                },
            )
        },
    )
}

private fun navigateToTab(
    navController: NavHostController,
    tab: TabDestination,
) {
    navController.navigate(tab) {
        popUpTo(navController.graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun DashboardInsightCardContent(
    uiState: DashboardUiState,
    isEditing: Boolean,
    onDismissInsight: (InsightType) -> Unit,
    onRestoreInsights: () -> Unit,
    onSelectInsight: (InsightType, DailyInsightContext) -> Unit,
) {
    val context = LocalContext.current
    val detailRepository = remember(context) { InsightDetailRepository(context) }
    val detailContext =
        remember(
            uiState.summary,
            uiState.stepGoal,
            uiState.goalSleepHours,
            uiState.selectedDate,
            uiState.userPreferences,
        ) {
            uiState.toDailyInsightContext()
        }

    AnimatedContent(
        targetState = uiState.currentInsight,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "dashboard_insight_card",
    ) { insight ->
        if (insight != null) {
            val detail = detailRepository.getDetail(insight, detailContext, uiState.currentInsightParams)
            val bodyText = buildInsightBodyText(insight, uiState, detail.cardDescription)
            InsightCard(
                title = detail.title,
                body = bodyText,
                icon = getInsightIcon(insight),
                onDismiss = { onDismissInsight(insight) },
                onShowDetails = { onSelectInsight(insight, detailContext) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            InsightRerunCard(
                text =
                    if (isEditing) {
                        stringResource(CoreUiR.string.card_title_insights)
                    } else {
                        stringResource(CoreUiR.string.insight_restore_dismissed, uiState.dismissedInsightCount)
                    },
                icon = if (isEditing) Icons.Default.Info else Icons.Default.Refresh,
                onRestore = if (isEditing) ({}) else onRestoreInsights,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun buildInsightBodyText(
    insight: InsightType,
    uiState: DashboardUiState,
    defaultDescription: String,
): String =
    if (insight == InsightType.REST_DAY_SUCCESS) {
        val sleepScore = uiState.summary?.sleepScore ?: 0f
        val duration = uiState.summary?.sleepDurationMinutes ?: 0
        val isPerfectSleep = sleepScore >= 85f && duration >= (uiState.goalSleepHours * 60).toInt()
        if (isPerfectSleep) {
            defaultDescription + " " + stringResource(CoreUiR.string.insight_rest_day_perfect_sleep)
        } else {
            defaultDescription
        }
    } else {
        defaultDescription
    }
