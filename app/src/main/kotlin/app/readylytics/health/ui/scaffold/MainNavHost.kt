package app.readylytics.health.ui.scaffold

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.readylytics.health.R
import app.readylytics.health.crashreport.buildIssueReportIntent
import app.readylytics.health.domain.insights.InsightParams
import app.readylytics.health.domain.insights.detail.DailyInsightContext
import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.feature.about.AboutScreen
import app.readylytics.health.feature.dashboard.DashboardRoute
import app.readylytics.health.feature.dashboard.InsightCard
import app.readylytics.health.feature.dashboard.InsightRerunCard
import app.readylytics.health.feature.dashboard.getInsightIcon
import app.readylytics.health.feature.dashboard.toDailyInsightContext
import app.readylytics.health.feature.insights.InsightDetailRepository
import app.readylytics.health.feature.insights.InsightDetailSheet
import app.readylytics.health.feature.settings.SettingsRoute
import app.readylytics.health.feature.sleep.SleepRoute
import app.readylytics.health.feature.vitals.bloodpressure.BloodPressureDetailRoute
import app.readylytics.health.feature.vitals.bodyfat.BodyFatDetailRoute
import app.readylytics.health.feature.vitals.heartrate.HeartRateDetailRoute
import app.readylytics.health.feature.vitals.overview.VitalsRoute
import app.readylytics.health.feature.vitals.steps.StepDetailRoute
import app.readylytics.health.feature.vitals.weight.WeightDetailRoute
import app.readylytics.health.feature.workouts.WorkoutDetailRoute
import app.readylytics.health.feature.workouts.WorkoutsRoute
import app.readylytics.health.ui.crashreport.CrashReportViewModel
import app.readylytics.health.ui.logcat.LogcatCaptureViewModel
import app.readylytics.health.ui.navigation.AppDestination
import app.readylytics.health.ui.navigation.TabDestination
import kotlinx.coroutines.launch
import app.readylytics.health.core.ui.R as CoreUiR

@Composable
fun MainNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = TabDestination.Dashboard,
        modifier = modifier,
        enterTransition = {
            val initialIndex = getTabIndex(initialState.destination)
            val targetIndex = getTabIndex(targetState.destination)

            val isEnteringDetail =
                targetState.destination.hasRoute(AppDestination.WorkoutDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.StepDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.HeartRateDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.WeightDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.BodyFatDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.BloodPressureDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.About::class)

            val direction =
                if (isEnteringDetail) {
                    AnimatedContentTransitionScope.SlideDirection.Start
                } else if (targetIndex > initialIndex) {
                    AnimatedContentTransitionScope.SlideDirection.Start
                } else {
                    AnimatedContentTransitionScope.SlideDirection.End
                }
            fadeIn(animationSpec = tween(300)) + slideIntoContainer(direction, tween(300))
        },
        exitTransition = {
            val initialIndex = getTabIndex(initialState.destination)
            val targetIndex = getTabIndex(targetState.destination)

            val isLeavingDetail =
                initialState.destination.hasRoute(AppDestination.WorkoutDetail::class) ||
                    initialState.destination.hasRoute(AppDestination.StepDetail::class) ||
                    initialState.destination.hasRoute(AppDestination.HeartRateDetail::class) ||
                    initialState.destination.hasRoute(AppDestination.WeightDetail::class) ||
                    initialState.destination.hasRoute(AppDestination.BodyFatDetail::class) ||
                    initialState.destination.hasRoute(AppDestination.BloodPressureDetail::class) ||
                    initialState.destination.hasRoute(AppDestination.About::class)
            val isEnteringDetail =
                targetState.destination.hasRoute(AppDestination.WorkoutDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.StepDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.HeartRateDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.WeightDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.BodyFatDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.BloodPressureDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.About::class)

            val direction =
                if (isLeavingDetail) {
                    // Keep slide out direction consistent with pop
                    AnimatedContentTransitionScope.SlideDirection.End
                } else if (isEnteringDetail || targetIndex > initialIndex) {
                    AnimatedContentTransitionScope.SlideDirection.Start
                } else {
                    AnimatedContentTransitionScope.SlideDirection.End
                }
            fadeOut(animationSpec = tween(300)) + slideOutOfContainer(direction, tween(300))
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(300)) +
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.End,
                    tween(300),
                )
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(300)) +
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.End,
                    tween(300),
                )
        },
    ) {
        composable<TabDestination.Dashboard> {
            var selectedInsightForDetails by remember { mutableStateOf<InsightType?>(null) }
            var selectedInsightParams by remember { mutableStateOf<InsightParams>(InsightParams.None) }
            var selectedInsightContext by remember { mutableStateOf<DailyInsightContext?>(null) }
            DashboardRoute(
                onNavigateToSleep = {
                    navController.navigate(TabDestination.Sleep) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onNavigateToWorkouts = {
                    navController.navigate(TabDestination.Workouts) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onNavigateToRhr = {
                    navController.navigate(TabDestination.Vitals) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onNavigateToSteps = {
                    navController.navigate(AppDestination.StepDetail)
                },
                onNavigateToHeartRate = {
                    navController.navigate(AppDestination.HeartRateDetail)
                },
                onNavigateToHrv = {
                    navController.navigate(TabDestination.Vitals) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onNavigateToWeight = {
                    navController.navigate(AppDestination.WeightDetail)
                },
                onNavigateToBodyFat = {
                    navController.navigate(AppDestination.BodyFatDetail)
                },
                onNavigateToBloodPressure = {
                    navController.navigate(AppDestination.BloodPressureDetail)
                },
                onNavigateToVitals = {
                    navController.navigate(TabDestination.Vitals) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
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
                            val detail =
                                detailRepository.getDetail(
                                    insight,
                                    detailContext,
                                    uiState.currentInsightParams,
                                )
                            val bodyText =
                                if (insight == InsightType.REST_DAY_SUCCESS) {
                                    val sleepScore = uiState.summary?.sleepScore ?: 0f
                                    val duration = uiState.summary?.sleepDurationMinutes ?: 0
                                    val isPerfectSleep =
                                        sleepScore >= 85f && duration >= (uiState.goalSleepHours * 60).toInt()
                                    if (isPerfectSleep) {
                                        detail.cardDescription + " " +
                                            stringResource(CoreUiR.string.insight_rest_day_perfect_sleep)
                                    } else {
                                        detail.cardDescription
                                    }
                                } else {
                                    detail.cardDescription
                                }

                            InsightCard(
                                title = detail.title,
                                body = bodyText,
                                icon = getInsightIcon(insight),
                                onDismiss = { onDismissInsight(insight) },
                                onShowDetails = {
                                    selectedInsightForDetails = insight
                                    selectedInsightContext = detailContext
                                    onOpenInsight(uiState.currentInsightParams)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            InsightRerunCard(
                                text =
                                    if (isEditing) {
                                        stringResource(R.string.card_title_insights)
                                    } else {
                                        stringResource(
                                            R.string.insight_restore_dismissed,
                                            uiState.dismissedInsightCount,
                                        )
                                    },
                                icon = if (isEditing) Icons.Default.Info else Icons.Default.Refresh,
                                onRestore = if (isEditing) ({}) else onRestoreInsights,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                },
            )
        }
        composable<TabDestination.Sleep> {
            SleepRoute()
        }
        composable<TabDestination.Vitals> {
            VitalsRoute(
                onNavigateToHrv = {},
                onNavigateToRhr = {},
            )
        }
        composable<TabDestination.Workouts> {
            WorkoutsRoute { id ->
                navController.navigate(AppDestination.WorkoutDetail(id))
            }
        }
        composable<AppDestination.WorkoutDetail> { backStackEntry ->
            val detail: AppDestination.WorkoutDetail = backStackEntry.toRoute()
            WorkoutDetailRoute(
                workoutId = detail.workoutId,
                onBack = { navController.popBackStack() },
            )
        }

        composable<AppDestination.StepDetail> {
            StepDetailRoute(
                onBack = { navController.popBackStack() },
            )
        }
        composable<AppDestination.HeartRateDetail> {
            HeartRateDetailRoute(
                onBack = { navController.popBackStack() },
            )
        }
        composable<AppDestination.WeightDetail> {
            WeightDetailRoute(
                onBack = { navController.popBackStack() },
            )
        }
        composable<AppDestination.BodyFatDetail> {
            BodyFatDetailRoute(
                onBack = { navController.popBackStack() },
            )
        }
        composable<AppDestination.BloodPressureDetail> {
            BloodPressureDetailRoute(
                onBack = { navController.popBackStack() },
            )
        }

        composable<AppDestination.About> {
            AboutScreen(
                onDismiss = { navController.popBackStack() },
            )
        }
        composable<TabDestination.Settings> {
            val context = LocalContext.current
            val crashReportViewModel: CrashReportViewModel = hiltViewModel()
            val logcatCaptureViewModel: LogcatCaptureViewModel = hiltViewModel()
            val coroutineScope = rememberCoroutineScope()
            SettingsRoute(
                onNavigateToAbout = {
                    navController.navigate(AppDestination.About)
                },
                onSendIssueReport = { request ->
                    coroutineScope.launch {
                        val crashText = if (request.hasCrashReport) crashReportViewModel.reportText() else null
                        val crashFile = if (request.hasCrashReport) crashReportViewModel.reportFile() else null
                        val logcatText =
                            if (request.includeLogcat) logcatCaptureViewModel.capture(request.logcatDurationMinutes) else null
                        val logcatFile = if (logcatText != null) logcatCaptureViewModel.captureFile() else null
                        val intent = buildIssueReportIntent(context, request, crashText, crashFile, logcatText, logcatFile)
                        context.startActivity(intent)
                        if (request.hasCrashReport) crashReportViewModel.consumeReport()
                    }
                },
            )
        }
    }
}

private fun getTabIndex(destination: NavDestination?): Int {
    if (destination == null) return -1
    val index =
        TabDestination.all.indexOfFirst { tab ->
            destination.hasRoute(tab::class)
        }
    if (index != -1) return index

    // WorkoutDetail is logically under Workouts
    if (destination.hasRoute(AppDestination.WorkoutDetail::class)) return 3
    // StepDetail, HeartRateDetail, WeightDetail, BodyFatDetail, and BloodPressureDetail are logically under Dashboard
    if (destination.hasRoute(AppDestination.StepDetail::class)) return 0
    if (destination.hasRoute(AppDestination.HeartRateDetail::class)) return 0
    if (destination.hasRoute(AppDestination.WeightDetail::class)) return 0
    if (destination.hasRoute(AppDestination.BodyFatDetail::class)) return 0
    if (destination.hasRoute(AppDestination.BloodPressureDetail::class)) return 0
    // About is logically under Settings
    if (destination.hasRoute(AppDestination.About::class)) return 4

    return -1
}
