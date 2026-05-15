package com.gregor.lauritz.healthdashboard.widgets.glance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gregor.lauritz.healthdashboard.data.repository.SmallWidgetConfig
import com.gregor.lauritz.healthdashboard.data.repository.WidgetConfigurationRepository
import com.gregor.lauritz.healthdashboard.data.repository.WidgetDataRepository
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SmallWidgetUpdaterTest {
    private val widgetDataRepository = mockk<WidgetDataRepository>()
    private val configRepository = mockk<WidgetConfigurationRepository>()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun updateSmallWidget_updates_widget_with_latest_data() =
        runTest {
            // Arrange
            val widgetId = 1
            val config =
                SmallWidgetConfig(
                    widgetId = widgetId,
                    metricType = "HRV",
                    showTrend = true,
                    showTimestamp = true,
                )
            val summary =
                DailySummary(
                    date = LocalDate.now(),
                    nocturnalHrv = 45,
                    nocturnalRhr = 52,
                    stepCount = 8500,
                    sleepDurationMinutes = 480,
                    deepSleepPercent = 20f,
                    remSleepPercent = 25f,
                )

            coEvery { configRepository.observeSmallWidgetConfig(widgetId) } returns flowOf(config)
            coEvery { widgetDataRepository.getLatestSummaryAsync() } returns summary

            // Act
            SmallWidgetUpdater.updateSmallWidget(
                context = context,
                widgetId = widgetId,
                configRepository = configRepository,
                widgetDataRepository = widgetDataRepository,
            )

            // Assert
            coVerify { configRepository.observeSmallWidgetConfig(widgetId) }
            coVerify { widgetDataRepository.getLatestSummaryAsync() }
        }
}
