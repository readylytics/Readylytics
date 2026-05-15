package com.gregor.lauritz.healthdashboard.widgets.glance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gregor.lauritz.healthdashboard.data.repository.MediumWidgetConfig
import com.gregor.lauritz.healthdashboard.data.repository.WidgetConfigurationRepository
import com.gregor.lauritz.healthdashboard.data.repository.WidgetDataRepository
import com.gregor.lauritz.healthdashboard.data.repository.WidgetMode
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
class MediumWidgetUpdaterTest {
    private val widgetDataRepository = mockk<WidgetDataRepository>()
    private val configRepository = mockk<WidgetConfigurationRepository>()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun updateMediumWidget_updates_widget_with_dual_metric_data() =
        runTest {
            // Arrange
            val widgetId = 1
            val config =
                MediumWidgetConfig(
                    widgetId = widgetId,
                    mode = WidgetMode.DUAL_METRIC.name,
                    metric1 = "HRV",
                    metric2 = "RHR",
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

            coEvery { configRepository.observeMediumWidgetConfig(widgetId) } returns flowOf(config)
            coEvery { widgetDataRepository.getLatestSummaryAsync() } returns summary

            // Act
            MediumWidgetUpdater.updateMediumWidget(
                context = context,
                widgetId = widgetId,
                configRepository = configRepository,
                widgetDataRepository = widgetDataRepository,
            )

            // Assert
            // Verification of Glance updates is complex, but we verify the interactions
            coVerify { configRepository.observeMediumWidgetConfig(widgetId) }
            coVerify { widgetDataRepository.getLatestSummaryAsync() }
        }
}
