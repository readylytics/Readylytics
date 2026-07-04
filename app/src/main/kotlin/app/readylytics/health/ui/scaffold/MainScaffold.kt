package app.readylytics.health.ui.scaffold

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
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
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.readylytics.health.R
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.ui.navigation.AppDestination
import app.readylytics.health.ui.navigation.TabDestination
import app.readylytics.health.ui.sync.SyncEvent
import app.readylytics.health.ui.sync.SyncViewModel
import kotlinx.coroutines.flow.collectLatest

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
    val showBottomBar =
        currentDestination?.let { dest ->
            !dest.hasRoute(AppDestination.WorkoutDetail::class) &&
                !dest.hasRoute(AppDestination.StepDetail::class) &&
                !dest.hasRoute(AppDestination.HeartRateDetail::class) &&
                !dest.hasRoute(AppDestination.WeightDetail::class) &&
                !dest.hasRoute(AppDestination.BodyFatDetail::class) &&
                !dest.hasRoute(AppDestination.BloodPressureDetail::class) &&
                !dest.hasRoute(AppDestination.About::class)
        } ?: true

    val snackbarHostState = remember { SnackbarHostState() }
    val syncCompletedMessage = stringResource(R.string.sync_completed)

    LaunchedEffect(Unit) {
        syncViewModel.syncEvents.collectLatest { event ->
            when (event) {
                SyncEvent.SyncCompleted -> {
                    snackbarHostState.showSnackbar(syncCompletedMessage)
                }
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = isSyncing,
        onRefresh = { syncViewModel.triggerManualSync() },
    ) {
        NavigationSuiteScaffold(
            layoutType =
                if (showBottomBar) {
                    NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
                } else {
                    NavigationSuiteType.None
                },
            navigationSuiteItems = {
                TabDestination.all.forEach { tab ->
                    val selected =
                        currentDestination
                            ?.hierarchy
                            ?.any { it.hasRoute(tab::class) } == true

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
            },
            modifier = modifier,
        ) {
            Scaffold(
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            ) { innerPadding ->
                val layoutDirection = LocalLayoutDirection.current
                // Keep padding stable to prevent list jumping when bottom bar hides
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

                    // Determinate "day X of Y" banner shown while a historical recalculation walks
                    // forward, so the recompute surfaces visible progress instead of a silent spinner.
                    recalcProgress?.takeIf { it.total > 0 }?.let { progress ->
                        RecalcProgressBanner(
                            current = progress.current,
                            total = progress.total,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = bottomPadding),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecalcProgressBanner(
    current: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
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
            Text(text = stringResource(R.string.recalculating_progress, current, total))
            LinearProgressIndicator(
                progress = { current.toFloat() / total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
