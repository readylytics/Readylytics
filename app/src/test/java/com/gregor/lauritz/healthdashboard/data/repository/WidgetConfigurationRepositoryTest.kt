package com.gregor.lauritz.healthdashboard.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WidgetConfigurationRepositoryTest {
    private val dataStore = mockk<DataStore<Preferences>>(relaxed = true)
    private lateinit var repository: WidgetConfigurationRepository

    @Before
    fun setup() {
        repository = WidgetConfigurationRepository(dataStore)
    }

    @Test
    fun saveSmallWidgetConfig_persists_configuration() = runTest {
        val capturedPreferences = slot<suspend (Preferences) -> Preferences>()
        coEvery { dataStore.edit(capture(capturedPreferences)) } returns Unit

        val config = SmallWidgetConfig(
            widgetId = 123,
            metricType = "HRV",
            showTrend = true,
            showTimestamp = true,
        )

        repository.saveSmallWidgetConfig(123, config)

        assertNotNull(capturedPreferences.captured)
    }

    @Test
    fun observeSmallWidgetConfig_returns_stored_configuration() = runTest {
        val json = Json { ignoreUnknownKeys = true }
        val config = SmallWidgetConfig(
            widgetId = 123,
            metricType = "RHR",
            showTrend = false,
            showTimestamp = true,
        )
        val serialized = json.encodeToString(SmallWidgetConfig.serializer(), config)

        val mockPreferences = mockk<Preferences>()
        coEvery { mockPreferences[stringPreferencesKey("widget_small_config_123")] } returns serialized
        coEvery { dataStore.data } returns flowOf(mockPreferences)

        val result = repository.observeSmallWidgetConfig(123).first()

        assertNotNull(result)
        assertEquals("RHR", result?.metricType)
        assertEquals(false, result?.showTrend)
    }

    @Test
    fun observeSmallWidgetConfig_returns_null_when_not_configured() = runTest {
        val mockPreferences = mockk<Preferences>()
        coEvery { mockPreferences[any()] } returns null
        coEvery { dataStore.data } returns flowOf(mockPreferences)

        val result = repository.observeSmallWidgetConfig(999).first()

        assertNull(result)
    }

    @Test
    fun saveMediumWidgetConfig_persists_dual_metric_mode() = runTest {
        val capturedPreferences = slot<suspend (Preferences) -> Preferences>()
        coEvery { dataStore.edit(capture(capturedPreferences)) } returns Unit

        val config = MediumWidgetConfig(
            widgetId = 456,
            mode = "DUAL_METRIC",
            metric1 = "HRV",
            metric2 = "RHR",
        )

        repository.saveMediumWidgetConfig(456, config)

        assertNotNull(capturedPreferences.captured)
    }

    @Test
    fun saveLargeWidgetConfig_persists_card_ids() = runTest {
        val capturedPreferences = slot<suspend (Preferences) -> Preferences>()
        coEvery { dataStore.edit(capture(capturedPreferences)) } returns Unit

        val config = LargeWidgetConfig(
            widgetId = 789,
            cardIds = listOf("SLEEP_SCORE", "HRV", "RHR", "STEPS"),
        )

        repository.saveLargeWidgetConfig(789, config)

        assertNotNull(capturedPreferences.captured)
    }

    @Test
    fun deleteWidgetConfig_removes_small_widget_configuration() = runTest {
        val capturedPreferences = slot<suspend (Preferences) -> Preferences>()
        coEvery { dataStore.edit(capture(capturedPreferences)) } returns Unit

        repository.deleteWidgetConfig(123, WidgetType.SMALL)

        assertNotNull(capturedPreferences.captured)
    }

    @Test
    fun deleteWidgetConfig_handles_all_widget_types() = runTest {
        val capturedPreferences = slot<suspend (Preferences) -> Preferences>()
        coEvery { dataStore.edit(capture(capturedPreferences)) } returns Unit

        repository.deleteWidgetConfig(100, WidgetType.MEDIUM)
        repository.deleteWidgetConfig(101, WidgetType.LARGE)

        assertNotNull(capturedPreferences.captured)
    }
}
