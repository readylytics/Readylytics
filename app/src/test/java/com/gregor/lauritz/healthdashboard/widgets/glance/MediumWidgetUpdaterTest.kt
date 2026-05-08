package com.gregor.lauritz.healthdashboard.widgets.glance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gregor.lauritz.healthdashboard.data.repository.WidgetConfigurationRepository
import com.gregor.lauritz.healthdashboard.data.repository.WidgetDataRepository
import com.gregor.lauritz.healthdashboard.data.repository.MediumWidgetConfig
import com.gregor.lauritz.healthdashboard.data.repository.WidgetMode
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.whenever
import org.mockito.kotlin.verify

@RunWith(MockitoJUnitRunner::class)
class MediumWidgetUpdaterTest {
    @Mock
    private lateinit var widgetDataRepository: WidgetDataRepository

    @Mock
    private lateinit var configRepository: WidgetConfigurationRepository

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testUpdateMediumWidget_dualMetricMode() = runTest {
        // Arrange
        val widgetId = 2
        val config = MediumWidgetConfig(
            widgetId = widgetId,
            mode = WidgetMode.DUAL_METRIC.name,
            metric1 = "HRV",
            metric2 = "RHR",
        )
        val summary = createDailySummary(
            nocturnalHrv = 45,
            nocturnalRhr = 52,
        )

        whenever(configRepository.observeMediumWidgetConfig(widgetId)).thenReturn(flowOf(config))
        whenever(widgetDataRepository.getLatestSummaryAsync()).thenReturn(summary)

        // Act
        MediumWidgetUpdater.updateMediumWidget(
            context,
            widgetId,
            widgetDataRepository,
            configRepository,
        )

        // Assert
        verify(configRepository).observeMediumWidgetConfig(widgetId)
        verify(widgetDataRepository).getLatestSummaryAsync()
    }

    @Test
    fun testUpdateMediumWidget_stepsProgressMode() = runTest {
        // Arrange
        val widgetId = 2
        val config = MediumWidgetConfig(
            widgetId = widgetId,
            mode = WidgetMode.STEPS_PROGRESS.name,
        )
        val summary = createDailySummary(stepCount = 7500)

        whenever(configRepository.observeMediumWidgetConfig(widgetId)).thenReturn(flowOf(config))
        whenever(widgetDataRepository.getLatestSummaryAsync()).thenReturn(summary)

        // Act
        MediumWidgetUpdater.updateMediumWidget(
            context,
            widgetId,
            widgetDataRepository,
            configRepository,
        )

        // Assert
        verify(configRepository).observeMediumWidgetConfig(widgetId)
        verify(widgetDataRepository).getLatestSummaryAsync()
    }

    @Test
    fun testUpdateMediumWidget_invalidMode() = runTest {
        // Arrange
        val widgetId = 2
        val config = MediumWidgetConfig(
            widgetId = widgetId,
            mode = "INVALID_MODE",
        )
        val summary = createDailySummary()

        whenever(configRepository.observeMediumWidgetConfig(widgetId)).thenReturn(flowOf(config))
        whenever(widgetDataRepository.getLatestSummaryAsync()).thenReturn(summary)

        // Act - should fallback to DUAL_METRIC
        MediumWidgetUpdater.updateMediumWidget(
            context,
            widgetId,
            widgetDataRepository,
            configRepository,
        )

        // Assert
        verify(configRepository).observeMediumWidgetConfig(widgetId)
    }

    private fun createDailySummary(
        nocturnalHrv: Int? = null,
        nocturnalRhr: Int? = null,
        stepCount: Int? = null,
    ) = DailySummary(
        date = "2024-01-01",
        nocturnalHrv = nocturnalHrv,
        nocturnalRhr = nocturnalRhr,
        stepCount = stepCount,
        sleepDurationMinutes = 480,
        deepSleepPercent = 20f,
        remSleepPercent = 25f,
    )
}
