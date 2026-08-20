package app.readylytics.health.data.preferences

import androidx.datastore.core.DataStore
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.sleep.SleepChartConfiguration
import app.readylytics.health.domain.sleep.SleepChartId
import app.readylytics.health.domain.sleep.SleepLayoutRepository
import app.readylytics.health.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.domain.sleep.SleepMetricCardId
import app.readylytics.health.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.domain.sleep.SleepTopCardId
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepLayoutRepositoryTest {
    private val dataStore = mockk<DataStore<SleepLayoutConfigurationsProto>>(relaxed = true)
    private lateinit var repository: SleepLayoutRepository

    @Before
    fun setup() {
        repository = SleepLayoutRepositoryImpl(dataStore, TestScope())
    }

    private fun topCardProto(
        cardId: String,
        isVisible: Boolean = true,
        position: Int = 0,
        requestedDisplayMode: String? = null,
    ): SleepTopCardConfigurationProto {
        val builder =
            SleepTopCardConfigurationProto
                .newBuilder()
                .setCardId(cardId)
                .setIsVisible(isVisible)
                .setPosition(position)
        if (requestedDisplayMode != null) {
            builder.setRequestedDisplayMode(requestedDisplayMode)
        }
        return builder.build()
    }

    private fun chartProto(
        chartId: String,
        isVisible: Boolean = true,
        position: Int = 0,
    ): SleepChartConfigurationProto =
        SleepChartConfigurationProto
            .newBuilder()
            .setChartId(chartId)
            .setIsVisible(isVisible)
            .setPosition(position)
            .build()

    private fun metricCardProto(
        cardId: String,
        isVisible: Boolean = true,
        position: Int = 0,
        requestedDisplayMode: String? = null,
    ): SleepMetricCardConfigurationProto {
        val builder =
            SleepMetricCardConfigurationProto
                .newBuilder()
                .setCardId(cardId)
                .setIsVisible(isVisible)
                .setPosition(position)
        if (requestedDisplayMode != null) {
            builder.setRequestedDisplayMode(requestedDisplayMode)
        }
        return builder.build()
    }

    @Test
    fun sleepTopCardConfigurations_returnsMappedDomainModels() =
        runTest {
            val proto =
                SleepLayoutConfigurationsProto
                    .newBuilder()
                    .addTopCards(topCardProto(SleepTopCardId.SLEEP_SCORE.name, position = 0))
                    .build()

            every { dataStore.data } returns flowOf(proto)

            val result = repository.sleepTopCardConfigurations().first()

            val scoreCard = result.find { it.cardId == SleepTopCardId.SLEEP_SCORE }
            assertNotNull(scoreCard)
            assertEquals(SleepTopCardId.SLEEP_SCORE, scoreCard.cardId)
            assertTrue(scoreCard.isVisible)
            assertEquals(0, scoreCard.position)
        }

    @Test
    fun sleepTopCardConfigurations_mapsRequestedDisplayModeWhenPresent() =
        runTest {
            val proto =
                SleepLayoutConfigurationsProto
                    .newBuilder()
                    .addTopCards(
                        topCardProto(
                            SleepTopCardId.SLEEP_DURATION_GAUGE.name,
                            requestedDisplayMode = DashboardCardDisplayMode.GAUGE.name,
                        ),
                    ).build()

            every { dataStore.data } returns flowOf(proto)

            val result = repository.sleepTopCardConfigurations().first()

            val gaugeCard = result.find { it.cardId == SleepTopCardId.SLEEP_DURATION_GAUGE }
            assertNotNull(gaugeCard)
            assertEquals(DashboardCardDisplayMode.GAUGE, gaugeCard.requestedDisplayMode)
        }

    @Test
    fun sleepTopCardConfigurations_mapsMissingRequestedDisplayModeToNull() =
        runTest {
            val proto =
                SleepLayoutConfigurationsProto
                    .newBuilder()
                    .addTopCards(topCardProto(SleepTopCardId.SLEEP_SCORE.name))
                    .build()

            every { dataStore.data } returns flowOf(proto)

            val result = repository.sleepTopCardConfigurations().first()

            val scoreCard = result.find { it.cardId == SleepTopCardId.SLEEP_SCORE }
            assertNotNull(scoreCard)
            assertNull(scoreCard.requestedDisplayMode)
        }

    @Test
    fun sleepTopCardConfigurations_unknownStoredModeStringMapsToNull() =
        runTest {
            val proto =
                SleepLayoutConfigurationsProto
                    .newBuilder()
                    .addTopCards(topCardProto(SleepTopCardId.SLEEP_SCORE.name, requestedDisplayMode = "INVALID_MODE"))
                    .build()

            every { dataStore.data } returns flowOf(proto)

            val result = repository.sleepTopCardConfigurations().first()

            val scoreCard = result.find { it.cardId == SleepTopCardId.SLEEP_SCORE }
            assertNotNull(scoreCard)
            assertNull(scoreCard.requestedDisplayMode)
        }

    @Test
    fun sleepChartConfigurations_returnsMappedDomainModels() =
        runTest {
            val proto =
                SleepLayoutConfigurationsProto
                    .newBuilder()
                    .addTrendCharts(chartProto(SleepChartId.SLEEP_DURATION_TREND.name, position = 0))
                    .build()

            every { dataStore.data } returns flowOf(proto)

            val result = repository.sleepChartConfigurations().first()

            val trendChart = result.find { it.chartId == SleepChartId.SLEEP_DURATION_TREND }
            assertNotNull(trendChart)
            assertTrue(trendChart.isVisible)
            assertEquals(0, trendChart.position)
        }

    @Test
    fun sleepMetricCardConfigurations_returnsMappedDomainModels() =
        runTest {
            val proto =
                SleepLayoutConfigurationsProto
                    .newBuilder()
                    .addMetricCards(metricCardProto(SleepMetricCardId.CIRCADIAN_CONSISTENCY.name, position = 0))
                    .build()

            every { dataStore.data } returns flowOf(proto)

            val result = repository.sleepMetricCardConfigurations().first()

            val consistencyCard = result.find { it.cardId == SleepMetricCardId.CIRCADIAN_CONSISTENCY }
            assertNotNull(consistencyCard)
            assertTrue(consistencyCard.isVisible)
            assertEquals(0, consistencyCard.position)
        }

    @Test
    fun updateSleepTopCardConfigurations_writesCorrectProtoField() =
        runTest {
            val capturedUpdate = slot<suspend (SleepLayoutConfigurationsProto) -> SleepLayoutConfigurationsProto>()
            coEvery { dataStore.updateData(capture(capturedUpdate)) } returns
                SleepLayoutConfigurationsProto.getDefaultInstance()

            val newConfigs =
                listOf(
                    SleepTopCardConfiguration(
                        SleepTopCardId.SLEEP_SCORE,
                        isVisible = true,
                        position = 1,
                        requestedDisplayMode = DashboardCardDisplayMode.BAR,
                    ),
                )

            repository.updateSleepTopCardConfigurations(newConfigs)

            val initialProto = SleepLayoutConfigurationsProto.getDefaultInstance()
            val updatedProto = capturedUpdate.captured(initialProto)

            assertEquals(1, updatedProto.topCardsCount)
            val protoCard = updatedProto.getTopCards(0)
            assertEquals(SleepTopCardId.SLEEP_SCORE.name, protoCard.cardId)
            assertEquals(DashboardCardDisplayMode.BAR.name, protoCard.requestedDisplayMode)
        }

    @Test
    fun updateSleepTopCardConfigurations_preservesChartsAndMetricCards() =
        runTest {
            var persisted =
                SleepLayoutConfigurationsProto
                    .newBuilder()
                    .addAllTopCards(
                        SettingsDefaults.DEFAULT_SLEEP_TOP_CARDS.map { SleepLayoutMapper.toTopCardProto(it) },
                    ).addAllTrendCharts(
                        SettingsDefaults.DEFAULT_SLEEP_CHARTS.map {
                            SleepLayoutMapper.toChartProto(it)
                        },
                    ).addAllMetricCards(
                        SettingsDefaults.DEFAULT_SLEEP_METRIC_CARDS.map { SleepLayoutMapper.toMetricCardProto(it) },
                    ).build()
            coEvery { dataStore.updateData(any()) } coAnswers {
                val transform = firstArg<suspend (SleepLayoutConfigurationsProto) -> SleepLayoutConfigurationsProto>()
                persisted = transform(persisted)
                persisted
            }

            val newCards =
                listOf(
                    SleepTopCardConfiguration(
                        SleepTopCardId.SLEEP_SCORE,
                        isVisible = false,
                        position = 1,
                    ),
                )
            repository.updateSleepTopCardConfigurations(newCards)

            assertEquals(1, persisted.topCardsCount)
            assertEquals(SleepTopCardId.SLEEP_SCORE.name, persisted.getTopCards(0).cardId)
            assertFalse(persisted.getTopCards(0).isVisible)
            assertEquals(SettingsDefaults.DEFAULT_SLEEP_CHARTS.size, persisted.trendChartsCount)
            assertEquals(SettingsDefaults.DEFAULT_SLEEP_METRIC_CARDS.size, persisted.metricCardsCount)
        }

    @Test
    fun updateSleepChartConfigurations_writesCorrectProtoField() =
        runTest {
            val capturedUpdate = slot<suspend (SleepLayoutConfigurationsProto) -> SleepLayoutConfigurationsProto>()
            coEvery { dataStore.updateData(capture(capturedUpdate)) } returns
                SleepLayoutConfigurationsProto.getDefaultInstance()

            val newCharts =
                listOf(
                    SleepChartConfiguration(
                        SleepChartId.SLEEP_DURATION_TREND,
                        isVisible = false,
                        position = 2,
                    ),
                )

            repository.updateSleepChartConfigurations(newCharts)

            val initialProto = SleepLayoutConfigurationsProto.getDefaultInstance()
            val updatedProto = capturedUpdate.captured(initialProto)

            assertEquals(1, updatedProto.trendChartsCount)
            val protoChart = updatedProto.getTrendCharts(0)
            assertEquals(SleepChartId.SLEEP_DURATION_TREND.name, protoChart.chartId)
            assertEquals(false, protoChart.isVisible)
            assertEquals(2, protoChart.position)
        }

    @Test
    fun updateSleepChartConfigurations_preservesTopCardsAndMetricCards() =
        runTest {
            var persisted =
                SleepLayoutConfigurationsProto
                    .newBuilder()
                    .addAllTopCards(
                        SettingsDefaults.DEFAULT_SLEEP_TOP_CARDS.map { SleepLayoutMapper.toTopCardProto(it) },
                    ).addAllTrendCharts(
                        SettingsDefaults.DEFAULT_SLEEP_CHARTS.map {
                            SleepLayoutMapper.toChartProto(it)
                        },
                    ).addAllMetricCards(
                        SettingsDefaults.DEFAULT_SLEEP_METRIC_CARDS.map { SleepLayoutMapper.toMetricCardProto(it) },
                    ).build()
            coEvery { dataStore.updateData(any()) } coAnswers {
                val transform = firstArg<suspend (SleepLayoutConfigurationsProto) -> SleepLayoutConfigurationsProto>()
                persisted = transform(persisted)
                persisted
            }

            val newCharts =
                listOf(
                    SleepChartConfiguration(
                        SleepChartId.SLEEP_DURATION_TREND,
                        isVisible = false,
                        position = 2,
                    ),
                )
            repository.updateSleepChartConfigurations(newCharts)

            assertEquals(1, persisted.trendChartsCount)
            assertEquals(SleepChartId.SLEEP_DURATION_TREND.name, persisted.getTrendCharts(0).chartId)
            assertFalse(persisted.getTrendCharts(0).isVisible)
            assertEquals(SettingsDefaults.DEFAULT_SLEEP_TOP_CARDS.size, persisted.topCardsCount)
            assertEquals(SettingsDefaults.DEFAULT_SLEEP_METRIC_CARDS.size, persisted.metricCardsCount)
        }

    @Test
    fun updateSleepMetricCardConfigurations_writesCorrectProtoField() =
        runTest {
            val capturedUpdate = slot<suspend (SleepLayoutConfigurationsProto) -> SleepLayoutConfigurationsProto>()
            coEvery { dataStore.updateData(capture(capturedUpdate)) } returns
                SleepLayoutConfigurationsProto.getDefaultInstance()

            val newMetricCards =
                listOf(
                    SleepMetricCardConfiguration(
                        SleepMetricCardId.DEEP_SLEEP,
                        isVisible = false,
                        position = 3,
                    ),
                )

            repository.updateSleepMetricCardConfigurations(newMetricCards)

            val initialProto = SleepLayoutConfigurationsProto.getDefaultInstance()
            val updatedProto = capturedUpdate.captured(initialProto)

            assertEquals(1, updatedProto.metricCardsCount)
            val protoCard = updatedProto.getMetricCards(0)
            assertEquals(SleepMetricCardId.DEEP_SLEEP.name, protoCard.cardId)
            assertEquals(false, protoCard.isVisible)
            assertEquals(3, protoCard.position)
        }

    @Test
    fun updateSleepMetricCardConfigurations_preservesTopCardsAndCharts() =
        runTest {
            var persisted =
                SleepLayoutConfigurationsProto
                    .newBuilder()
                    .addAllTopCards(
                        SettingsDefaults.DEFAULT_SLEEP_TOP_CARDS.map { SleepLayoutMapper.toTopCardProto(it) },
                    ).addAllTrendCharts(
                        SettingsDefaults.DEFAULT_SLEEP_CHARTS.map {
                            SleepLayoutMapper.toChartProto(it)
                        },
                    ).addAllMetricCards(
                        SettingsDefaults.DEFAULT_SLEEP_METRIC_CARDS.map { SleepLayoutMapper.toMetricCardProto(it) },
                    ).build()
            coEvery { dataStore.updateData(any()) } coAnswers {
                val transform = firstArg<suspend (SleepLayoutConfigurationsProto) -> SleepLayoutConfigurationsProto>()
                persisted = transform(persisted)
                persisted
            }

            val newMetricCards =
                listOf(
                    SleepMetricCardConfiguration(
                        SleepMetricCardId.DEEP_SLEEP,
                        isVisible = false,
                        position = 3,
                    ),
                )
            repository.updateSleepMetricCardConfigurations(newMetricCards)

            assertEquals(1, persisted.metricCardsCount)
            assertEquals(SleepMetricCardId.DEEP_SLEEP.name, persisted.getMetricCards(0).cardId)
            assertFalse(persisted.getMetricCards(0).isVisible)
            assertEquals(SettingsDefaults.DEFAULT_SLEEP_TOP_CARDS.size, persisted.topCardsCount)
            assertEquals(SettingsDefaults.DEFAULT_SLEEP_CHARTS.size, persisted.trendChartsCount)
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun init_appendsMissingDefaultTopCardsOnceAndRenumbersPositions() =
        runTest {
            var persisted =
                SleepLayoutConfigurationsProto
                    .newBuilder()
                    .addTopCards(topCardProto(SleepTopCardId.SLEEP_SCORE.name, position = 4))
                    .addAllTrendCharts(SettingsDefaults.DEFAULT_SLEEP_CHARTS.map { SleepLayoutMapper.toChartProto(it) })
                    .addAllMetricCards(
                        SettingsDefaults.DEFAULT_SLEEP_METRIC_CARDS.map { SleepLayoutMapper.toMetricCardProto(it) },
                    ).build()
            coEvery { dataStore.updateData(any()) } coAnswers {
                val transform = firstArg<suspend (SleepLayoutConfigurationsProto) -> SleepLayoutConfigurationsProto>()
                persisted = transform(persisted)
                persisted
            }

            val testScope = TestScope(testScheduler)
            SleepLayoutRepositoryImpl(dataStore, testScope)
            testScope.advanceUntilIdle()

            assertEquals(SettingsDefaults.DEFAULT_SLEEP_TOP_CARDS.size, persisted.topCardsCount)

            val appendedCards = persisted.topCardsList.filter { it.cardId != SleepTopCardId.SLEEP_SCORE.name }
            appendedCards.forEachIndexed { index, protoCard ->
                assertEquals(5 + index, protoCard.position)
            }
            assertEquals(1, persisted.topCardsList.count { it.cardId == SleepTopCardId.SLEEP_SCORE.name })
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun init_doesNotDuplicateDefaultsOnRepeatedInit() =
        runTest {
            var persisted =
                SleepLayoutConfigurationsProto
                    .newBuilder()
                    .addTopCards(topCardProto(SleepTopCardId.SLEEP_SCORE.name, position = 0))
                    .addTrendCharts(chartProto(SleepChartId.SLEEP_DURATION_TREND.name, position = 0))
                    .addMetricCards(metricCardProto(SleepMetricCardId.CIRCADIAN_CONSISTENCY.name, position = 0))
                    .build()
            coEvery { dataStore.updateData(any()) } coAnswers {
                val transform = firstArg<suspend (SleepLayoutConfigurationsProto) -> SleepLayoutConfigurationsProto>()
                persisted = transform(persisted)
                persisted
            }

            val firstScope = TestScope(testScheduler)
            SleepLayoutRepositoryImpl(dataStore, firstScope)
            firstScope.advanceUntilIdle()
            assertEquals(SettingsDefaults.DEFAULT_SLEEP_TOP_CARDS.size, persisted.topCardsCount)
            assertEquals(SettingsDefaults.DEFAULT_SLEEP_CHARTS.size, persisted.trendChartsCount)
            assertEquals(SettingsDefaults.DEFAULT_SLEEP_METRIC_CARDS.size, persisted.metricCardsCount)

            val secondScope = TestScope(testScheduler)
            SleepLayoutRepositoryImpl(dataStore, secondScope)
            secondScope.advanceUntilIdle()

            assertEquals(SettingsDefaults.DEFAULT_SLEEP_TOP_CARDS.size, persisted.topCardsCount)
            assertEquals(SettingsDefaults.DEFAULT_SLEEP_CHARTS.size, persisted.trendChartsCount)
            assertEquals(SettingsDefaults.DEFAULT_SLEEP_METRIC_CARDS.size, persisted.metricCardsCount)
        }

    @Test
    fun sleepTopCardConfigurations_unknownCardIdIsDroppedAndDefaultsStillAppended() =
        runTest {
            val proto =
                SleepLayoutConfigurationsProto
                    .newBuilder()
                    .addTopCards(topCardProto("FUTURE_TOP_CARD"))
                    .addTopCards(topCardProto(SleepTopCardId.SLEEP_SCORE.name, position = 1))
                    .build()

            every { dataStore.data } returns flowOf(proto)

            val result = repository.sleepTopCardConfigurations().first()

            assertNotNull(result.find { it.cardId == SleepTopCardId.SLEEP_SCORE })
            assertEquals(SettingsDefaults.DEFAULT_SLEEP_TOP_CARDS.size, result.size)
        }
}
