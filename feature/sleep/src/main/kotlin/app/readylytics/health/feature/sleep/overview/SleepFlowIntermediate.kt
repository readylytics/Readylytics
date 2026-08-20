package app.readylytics.health.feature.sleep.overview

import androidx.compose.runtime.Immutable
import app.readylytics.health.core.model.domain.layout.LayoutManagementDelegate
import app.readylytics.health.domain.sleep.SleepChartConfiguration
import app.readylytics.health.domain.sleep.SleepChartId
import app.readylytics.health.domain.sleep.SleepLayoutRepository
import app.readylytics.health.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.domain.sleep.SleepMetricCardManagementDelegate
import app.readylytics.health.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.domain.sleep.SleepTopCardManagementDelegate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

@Immutable
internal data class SleepTopCardState(
    val isManagingTopCards: Boolean,
    val topCardConfigurations: List<SleepTopCardConfiguration>,
)

@Immutable
internal data class SleepChartState(
    val isManagingCharts: Boolean,
    val chartConfigurations: List<SleepChartConfiguration>,
)

@Immutable
internal data class SleepMetricCardState(
    val isManagingMetricCards: Boolean,
    val metricCardConfigurations: List<SleepMetricCardConfiguration>,
)

internal fun createSleepTopCardStateFlow(
    delegate: SleepTopCardManagementDelegate,
    repository: SleepLayoutRepository,
): Flow<SleepTopCardState> =
    combine(
        delegate.isManaging,
        delegate.pendingConfigs,
        repository.sleepTopCardConfigurations(),
    ) { isManaging, pending, configs ->
        SleepTopCardState(
            isManagingTopCards = isManaging,
            topCardConfigurations = pending ?: configs,
        )
    }

internal fun createSleepChartStateFlow(
    delegate: LayoutManagementDelegate<SleepChartConfiguration, SleepChartId>,
    repository: SleepLayoutRepository,
): Flow<SleepChartState> =
    combine(
        delegate.isManaging,
        delegate.pendingConfigs,
        repository.sleepChartConfigurations(),
    ) { isManaging, pending, configs ->
        SleepChartState(
            isManagingCharts = isManaging,
            chartConfigurations = pending ?: configs,
        )
    }

internal fun createSleepMetricCardStateFlow(
    delegate: SleepMetricCardManagementDelegate,
    repository: SleepLayoutRepository,
): Flow<SleepMetricCardState> =
    combine(
        delegate.isManaging,
        delegate.pendingConfigs,
        repository.sleepMetricCardConfigurations(),
    ) { isManaging, pending, configs ->
        SleepMetricCardState(
            isManagingMetricCards = isManaging,
            metricCardConfigurations = pending ?: configs,
        )
    }
