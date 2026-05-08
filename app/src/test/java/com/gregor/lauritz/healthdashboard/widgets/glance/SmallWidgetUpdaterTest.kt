package com.gregor.lauritz.healthdashboard.widgets.glance

import android.content.Context
import androidx.datastore.preferences.core.PreferencesFactory
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.gregor.lauritz.healthdashboard.data.repository.WidgetConfigurationRepository
import com.gregor.lauritz.healthdashboard.data.repository.WidgetDataRepository
import com.gregor.lauritz.healthdashboard.data.repository.SmallWidgetConfig
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.model.MetricType
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
class SmallWidgetUpdaterTest {
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
    fun testUpdateSmallWidget_success() = runTest {
        // Arrange
        val widgetId = 1
        val config = SmallWidgetConfig(
            widgetId = widgetId,
            metricType = "HRV",
        )
        val summary = createDailySummary(nocturnalHrv = 45)

        whenever(configRepository.observeSmallWidgetConfig(widgetId)).thenReturn(flowOf(config))
        whenever(widgetDataRepository.getLatestSummaryAsync()).thenReturn(summary)

        // Act
        SmallWidgetUpdater.updateSmallWidget(
            context,
            widgetId,
            widgetDataRepository,
            configRepository,
        )

        // Assert
        verify(configRepository).observeSmallWidgetConfig(widgetId)
        verify(widgetDataRepository).getLatestSummaryAsync()
    }

    @Test
    fun testUpdateSmallWidget_noData() = runTest {
        // Arrange
        val widgetId = 1
        val config = SmallWidgetConfig(
            widgetId = widgetId,
            metricType = "HRV",
        )

        whenever(configRepository.observeSmallWidgetConfig(widgetId)).thenReturn(flowOf(config))
        whenever(widgetDataRepository.getLatestSummaryAsync()).thenReturn(null)

        // Act
        SmallWidgetUpdater.updateSmallWidget(
            context,
            widgetId,
            widgetDataRepository,
            configRepository,
        )

        // Assert
        verify(configRepository).observeSmallWidgetConfig(widgetId)
        verify(widgetDataRepository).getLatestSummaryAsync()
    }

    @Test
    fun testUpdateSmallWidget_noConfig() = runTest {
        // Arrange
        val widgetId = 1

        whenever(configRepository.observeSmallWidgetConfig(widgetId)).thenReturn(flowOf(null))

        // Act
        SmallWidgetUpdater.updateSmallWidget(
            context,
            widgetId,
            widgetDataRepository,
            configRepository,
        )

        // Assert
        verify(configRepository).observeSmallWidgetConfig(widgetId)
    }

    private fun createDailySummary(
        nocturnalHrv: Int? = null,
        nocturnalRhr: Int? = null,
        sleepScore: Int? = null,
        readinessScore: Int? = null,
        stepCount: Int? = null,
    ) = DailySummary(
        date = "2024-01-01",
        nocturnalHrv = nocturnalHrv,
        nocturnalRhr = nocturnalRhr,
        sleepScore = sleepScore,
        readinessScore = readinessScore,
        stepCount = stepCount,
        sleepDurationMinutes = 480,
        deepSleepPercent = 20f,
        remSleepPercent = 25f,
        paiScore = 85,
        strainRatio = 0.9f,
    )
}
