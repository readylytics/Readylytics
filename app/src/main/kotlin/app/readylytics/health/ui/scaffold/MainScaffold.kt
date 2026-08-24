package app.readylytics.health.ui.scaffold

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScope
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.readylytics.health.R
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.sync.RecalcProgress
import app.readylytics.health.core.model.domain.sync.ResyncPhase
import app.readylytics.health.core.model.domain.sync.fraction
import app.readylytics.health.ui.navigation.AppDestination
import app.readylytics.health.ui.navigation.TabDestination
import app.readylytics.health.ui.sync.SyncEvent
import app.readylytics.health.ui.sync.SyncViewModel
import kotlinx.coroutines.flow.collectLatest
import app.readylytics.health.core.ui.R as CoreUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(
    modifier: Modifier = Modifier,
    syncViewModel: SyncViewModel = hiltViewModel(),
) {
    val isSyncing by syncViewModel.isSyncing.collectAsStateWithLifecycle()
    val recalcProgress by syncViewModel.recalcProgress.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val showBottomBar = shouldShowBottomBar(currentDestination)
    val snackbarHostState = remember { SnackbarHostState() }
    val syncCompletedMessage = stringResource(R.string.sync_completed)

    LaunchedEffect(Unit) {
        syncViewModel.syncEvents.collectLatest { event ->
            if (event is SyncEvent.SyncCompleted) {
                snackbarHostState.showSnackbar(syncCompletedMessage)
            }
        }
    }

    val isSyncProgressScreen = currentDestination?.hasRoute(AppDestination.SyncProgress::class) == true

    PullToRefreshBox(
        isRefreshing = isSyncing && !isSyncProgressScreen,
        onRefresh = { syncViewModel.triggerManualSync() },
        enabled = !isSyncProgressScreen,
    ) {
        NavigationSuiteScaffold(
            layoutType =
                if (showBottomBar) {
                    NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfoV2())
                } else {
                    NavigationSuiteType.None
                },
            navigationSuiteItems = {
                renderNavigationSuiteItems(navController, currentDestination)
            },
            modifier = modifier,
        ) {
            Scaffold(
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            ) { innerPadding ->
                MainScaffoldContent(
                    navController = navController,
                    innerPadding = innerPadding,
                    recalcProgress = recalcProgress,
                    isSyncProgressScreen = isSyncProgressScreen,
                )
            }
        }
    }
}

private fun shouldShowBottomBar(currentDestination: NavDestination?): Boolean =
    currentDestination?.let { dest ->
        !dest.hasRoute(AppDestination.WorkoutDetail::class) &&
            !dest.hasRoute(AppDestination.StepDetail::class) &&
            !dest.hasRoute(AppDestination.HeartRateDetail::class) &&
            !dest.hasRoute(AppDestination.WeightDetail::class) &&
            !dest.hasRoute(AppDestination.BodyFatDetail::class) &&
            !dest.hasRoute(AppDestination.BloodPressureDetail::class) &&
            !dest.hasRoute(AppDestination.About::class) &&
            !dest.hasRoute(AppDestination.SyncProgress::class)
    } ?: true

private fun NavigationSuiteScope.renderNavigationSuiteItems(
    navController: NavHostController,
    currentDestination: NavDestination?,
) {
    TabDestination.all.forEach { tab ->
        val selected = currentDestination?.hierarchy?.any { it.hasRoute(tab::class) } == true
        item(
            icon = {
                Icon(
                    imageVector = if (selected) tab.selectedIcon else tab.icon,
                    contentDescription = stringResource(tab.labelRes),
                )
            },
            label = { Text(stringResource(tab.labelRes)) },
            selected = selected,
            onClick = {
                navController.navigate(tab) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            },
        )
    }
}

@Composable
private fun MainScaffoldContent(
    navController: NavHostController,
    innerPadding: PaddingValues,
    recalcProgress: RecalcProgress?,
    isSyncProgressScreen: Boolean,
) {
    val layoutDirection = LocalLayoutDirection.current
    val bottomPadding =
        remember(innerPadding.calculateBottomPadding()) {
            innerPadding.calculateBottomPadding()
        }

    Box(modifier = Modifier.fillMaxSize()) {
        MainNavHost(
            navController = navController,
            modifier =
                Modifier.padding(
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    top = innerPadding.calculateTopPadding(),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                    bottom = bottomPadding,
                ),
        )

        recalcProgress?.takeIf { !isSyncProgressScreen }?.let { progress ->
            RecalcProgressBanner(
                progress = progress,
                onClick = {
                    navController.navigate(AppDestination.SyncProgress) {
                        launchSingleTop = true
                    }
                },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = bottomPadding),
            )
        }
    }
}

@Composable
private fun RecalcProgressBanner(
    progress: RecalcProgress,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = MaterialTheme.spacing.medium,
                        vertical = MaterialTheme.spacing.small,
                    ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        ) {
            val text =
                when (progress.phase) {
                    ResyncPhase.INGEST ->
                        if (progress.total > 0) {
                            stringResource(CoreUiR.string.resync_phase_ingest, progress.current, progress.total)
                        } else {
                            stringResource(CoreUiR.string.resync_phase_ingest_indeterminate, progress.current)
                        }
                    ResyncPhase.PRUNE -> stringResource(CoreUiR.string.resync_phase_prune)
                    ResyncPhase.RECONCILE -> stringResource(CoreUiR.string.resync_phase_reconcile)
                    ResyncPhase.RECOMPUTE ->
                        stringResource(CoreUiR.string.recalculating_progress, progress.current, progress.total)
                }
            Text(text = text)
            LinearProgressIndicator(
                progress = { progress.fraction() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
