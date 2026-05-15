package com.gregor.lauritz.healthdashboard.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WidgetConfigurationRepositoryTest {
    private val dataStore = mockk<DataStore<Preferences>>(relaxed = true)
    private lateinit var repository: WidgetConfigurationRepository

    @Before
    fun setup() {
        repository = WidgetConfigurationRepository(dataStore)
    }

    @Test
    fun saveSmallWidgetConfig_persists_configuration() =
        runTest {
            val mockMutablePreferences = mockk<MutablePreferences>(relaxed = true)

            // Mocking the extension function edit
            mockkStatic("androidx.datastore.preferences.core.PreferencesKt")
            coEvery { any<DataStore<Preferences>>().edit(any()) } coAnswers {
                val transform = it.invocation.args[1] as suspend (MutablePreferences) -> Unit
                transform(mockMutablePreferences)
                mockk<Preferences>()
            }

            val config =
                SmallWidgetConfig(
                    widgetId = 123,
                    metricType = "HRV",
                    showTrend = true,
                    showTimestamp = true,
                )

            repository.saveSmallWidgetConfig(123, config)

            coVerify { mockMutablePreferences[stringPreferencesKey("widget_small_config_123")] = any() }

            unmockkStatic("androidx.datastore.preferences.core.PreferencesKt")
        }

    @Test
    fun observeSmallWidgetConfig_returns_stored_configuration() =
        runTest {
            val json = Json { ignoreUnknownKeys = true }
            val config =
                SmallWidgetConfig(
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
    fun observeSmallWidgetConfig_returns_null_when_not_configured() =
        runTest {
            val mockPreferences = mockk<Preferences>()
            coEvery { mockPreferences[any<Preferences.Key<Any>>()] } returns null
            coEvery { dataStore.data } returns flowOf(mockPreferences)

            val result = repository.observeSmallWidgetConfig(999).first()

            assertNull(result)
        }

    @Test
    fun saveMediumWidgetConfig_persists_dual_metric_mode() =
        runTest {
            val mockMutablePreferences = mockk<MutablePreferences>(relaxed = true)

            mockkStatic("androidx.datastore.preferences.core.PreferencesKt")
            coEvery { any<DataStore<Preferences>>().edit(any()) } coAnswers {
                val transform = it.invocation.args[1] as suspend (MutablePreferences) -> Unit
                transform(mockMutablePreferences)
                mockk<Preferences>()
            }

            val config =
                MediumWidgetConfig(
                    widgetId = 456,
                    mode = "DUAL_METRIC",
                    metric1 = "HRV",
                    metric2 = "RHR",
                )

            repository.saveMediumWidgetConfig(456, config)

            coVerify { mockMutablePreferences[stringPreferencesKey("widget_medium_config_456")] = any() }

            unmockkStatic("androidx.datastore.preferences.core.PreferencesKt")
        }

    @Test
    fun saveLargeWidgetConfig_persists_card_ids() =
        runTest {
            val mockMutablePreferences = mockk<MutablePreferences>(relaxed = true)

            mockkStatic("androidx.datastore.preferences.core.PreferencesKt")
            coEvery { any<DataStore<Preferences>>().edit(any()) } coAnswers {
                val transform = it.invocation.args[1] as suspend (MutablePreferences) -> Unit
                transform(mockMutablePreferences)
                mockk<Preferences>()
            }

            val config =
                LargeWidgetConfig(
                    widgetId = 789,
                    cardIds = listOf("SLEEP_SCORE", "HRV", "RHR", "STEPS"),
                )

            repository.saveLargeWidgetConfig(789, config)

            coVerify { mockMutablePreferences[stringPreferencesKey("widget_large_config_789")] = any() }

            unmockkStatic("androidx.datastore.preferences.core.PreferencesKt")
        }

    @Test
    fun deleteWidgetConfig_removes_small_widget_configuration() =
        runTest {
            val mockMutablePreferences = mockk<MutablePreferences>(relaxed = true)

            mockkStatic("androidx.datastore.preferences.core.PreferencesKt")
            coEvery { any<DataStore<Preferences>>().edit(any()) } coAnswers {
                val transform = it.invocation.args[1] as suspend (MutablePreferences) -> Unit
                transform(mockMutablePreferences)
                mockk<Preferences>()
            }

            repository.deleteWidgetConfig(123, WidgetType.SMALL)

            coVerify { mockMutablePreferences.remove(stringPreferencesKey("widget_small_config_123")) }

            unmockkStatic("androidx.datastore.preferences.core.PreferencesKt")
        }
}
