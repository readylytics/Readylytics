package app.readylytics.health.data.preferences

import androidx.datastore.core.DataStore
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.vitals.VitalsChartId
import app.readylytics.health.domain.vitals.VitalsLayoutRepository
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VitalsLayoutRepositoryTest {
    private val dataStore = mockk<DataStore<VitalsLayoutConfigurationsProto>>(relaxed = true)
    private lateinit var repository: VitalsLayoutRepository

    @Before
    fun setup() {
        repository = VitalsLayoutRepositoryImpl(dataStore, TestScope())
    }

    private fun cardProto(
        cardId: String,
        isVisible: Boolean = true,
        position: Int = 0,
        requestedDisplayMode: String? = null,
    ): VitalsCardConfigurationProto {
        val builder =
            VitalsCardConfigurationProto
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
    ): VitalsChartConfigurationProto =
        VitalsChartConfigurationProto
            .newBuilder()
            .setChartId(chartId)
            .setIsVisible(isVisible)
            .setPosition(position)
            .build()

    @Test
    fun vitalsCardConfigurations_returnsMappedDomainModels() =
        runTest {
            val proto =
                VitalsLayoutConfigurationsProto
                    .newBuilder()
                    .addVitalsCards(
                        VitalsCardConfigurationProto
                            .newBuilder()
                            .setCardId(CardId.RESTING_HR.name)
                            .setIsVisible(true)
                            .setPosition(0)
                            .build(),
                    ).build()

            every { dataStore.data } returns flowOf(proto)

            val result = repository.vitalsCardConfigurations().first()

            val restingHrCard = result.find { it.cardId == CardId.RESTING_HR }
            assertNotNull(restingHrCard)
            assertEquals(CardId.RESTING_HR, restingHrCard.cardId)
            assertTrue(restingHrCard.isVisible)
            assertEquals(0, restingHrCard.position)
        }

    @Test
    fun vitalsCardConfigurations_mapsRequestedDisplayModeWhenPresent() =
        runTest {
            val proto =
                VitalsLayoutConfigurationsProto
                    .newBuilder()
                    .addVitalsCards(
                        cardProto(
                            CardId.HRV.name,
                            requestedDisplayMode = DashboardCardDisplayMode.GAUGE.name,
                        ),
                    ).build()

            every { dataStore.data } returns flowOf(proto)

            val result = repository.vitalsCardConfigurations().first()

            val hrvCard = result.find { it.cardId == CardId.HRV }
            assertNotNull(hrvCard)
            assertEquals(DashboardCardDisplayMode.GAUGE, hrvCard.requestedDisplayMode)
        }

    @Test
    fun vitalsCardConfigurations_mapsMissingRequestedDisplayModeToNull() =
        runTest {
            val proto =
                VitalsLayoutConfigurationsProto
                    .newBuilder()
                    .addVitalsCards(cardProto(CardId.HRV.name))
                    .build()

            every { dataStore.data } returns flowOf(proto)

            val result = repository.vitalsCardConfigurations().first()

            val hrvCard = result.find { it.cardId == CardId.HRV }
            assertNotNull(hrvCard)
            assertEquals(null, hrvCard.requestedDisplayMode)
        }

    @Test
    fun vitalsCardConfigurations_unknownStoredModeStringMapsToNull() =
        runTest {
            val proto =
                VitalsLayoutConfigurationsProto
                    .newBuilder()
                    .addVitalsCards(cardProto(CardId.HRV.name, requestedDisplayMode = "NOT_A_REAL_MODE"))
                    .build()

            every { dataStore.data } returns flowOf(proto)

            val result = repository.vitalsCardConfigurations().first()

            val hrvCard = result.find { it.cardId == CardId.HRV }
            assertNotNull(hrvCard)
            assertNull(hrvCard.requestedDisplayMode)
        }

    @Test
    fun vitalsChartConfigurations_returnsMappedDomainModels() =
        runTest {
            val proto =
                VitalsLayoutConfigurationsProto
                    .newBuilder()
                    .addTrendCharts(
                        VitalsChartConfigurationProto
                            .newBuilder()
                            .setChartId(VitalsChartId.HRV_TREND.name)
                            .setIsVisible(true)
                            .setPosition(2)
                            .build(),
                    ).build()

            every { dataStore.data } returns flowOf(proto)

            val result = repository.vitalsChartConfigurations().first()

            val hrvTrend = result.find { it.chartId == VitalsChartId.HRV_TREND }
            assertNotNull(hrvTrend)
            assertTrue(hrvTrend.isVisible)
            assertEquals(2, hrvTrend.position)
        }

    @Test
    fun updateVitalsCardConfigurations_writesCorrectProtoField() =
        runTest {
            val capturedUpdate = slot<suspend (VitalsLayoutConfigurationsProto) -> VitalsLayoutConfigurationsProto>()
            coEvery { dataStore.updateData(capture(capturedUpdate)) } returns
                VitalsLayoutConfigurationsProto.getDefaultInstance()

            val newConfigs =
                listOf(
                    CardConfiguration(
                        CardId.HRV,
                        isVisible = true,
                        position = 1,
                        requestedDisplayMode = DashboardCardDisplayMode.BAR,
                    ),
                )

            repository.updateVitalsCardConfigurations(newConfigs)

            val initialProto = VitalsLayoutConfigurationsProto.getDefaultInstance()
            val updatedProto = capturedUpdate.captured(initialProto)

            assertEquals(1, updatedProto.vitalsCardsCount)
            val protoCard = updatedProto.getVitalsCards(0)
            assertEquals(CardId.HRV.name, protoCard.cardId)
            assertEquals(DashboardCardDisplayMode.BAR.name, protoCard.requestedDisplayMode)
        }

    @Test
    fun updateVitalsChartConfigurations_writesCorrectProtoField() =
        runTest {
            val capturedUpdate = slot<suspend (VitalsLayoutConfigurationsProto) -> VitalsLayoutConfigurationsProto>()
            coEvery { dataStore.updateData(capture(capturedUpdate)) } returns
                VitalsLayoutConfigurationsProto.getDefaultInstance()

            val newCharts =
                listOf(
                    app.readylytics.health.domain.vitals.VitalsChartConfiguration(
                        VitalsChartId.SPO2_TREND,
                        isVisible = false,
                        position = 4,
                    ),
                )

            repository.updateVitalsChartConfigurations(newCharts)

            val initialProto = VitalsLayoutConfigurationsProto.getDefaultInstance()
            val updatedProto = capturedUpdate.captured(initialProto)

            assertEquals(1, updatedProto.trendChartsCount)
            val protoChart = updatedProto.getTrendCharts(0)
            assertEquals(VitalsChartId.SPO2_TREND.name, protoChart.chartId)
            assertEquals(false, protoChart.isVisible)
            assertEquals(4, protoChart.position)
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun init_appendsMissingDefaultVitalsCardsOnceAndRenumbersPositions() =
        runTest {
            var persisted =
                VitalsLayoutConfigurationsProto
                    .newBuilder()
                    .addVitalsCards(cardProto(CardId.RESTING_HR.name, position = 4))
                    .addAllTrendCharts(
                        SettingsDefaults.DEFAULT_VITALS_CHARTS.map { VitalsLayoutMapper.toChartProto(it) },
                    ).build()
            coEvery { dataStore.updateData(any()) } coAnswers {
                val transform = firstArg<suspend (VitalsLayoutConfigurationsProto) -> VitalsLayoutConfigurationsProto>()
                persisted = transform(persisted)
                persisted
            }

            val testScope = TestScope(testScheduler)
            VitalsLayoutRepositoryImpl(dataStore, testScope)
            testScope.advanceUntilIdle()

            assertEquals(SettingsDefaults.DEFAULT_VITALS_CARDS.size, persisted.vitalsCardsCount)

            val appendedCards = persisted.vitalsCardsList.filter { it.cardId != CardId.RESTING_HR.name }
            appendedCards.forEachIndexed { index, protoCard ->
                assertEquals(5 + index, protoCard.position)
            }
            assertEquals(1, persisted.vitalsCardsList.count { it.cardId == CardId.RESTING_HR.name })
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun init_appendsMissingDefaultChartsOnceAndRenumbersPositions() =
        runTest {
            var persisted =
                VitalsLayoutConfigurationsProto
                    .newBuilder()
                    .addTrendCharts(chartProto(VitalsChartId.HRV_TREND.name, position = 2))
                    .addAllVitalsCards(SettingsDefaults.DEFAULT_VITALS_CARDS.map { VitalsLayoutMapper.toCardProto(it) })
                    .build()
            coEvery { dataStore.updateData(any()) } coAnswers {
                val transform = firstArg<suspend (VitalsLayoutConfigurationsProto) -> VitalsLayoutConfigurationsProto>()
                persisted = transform(persisted)
                persisted
            }

            val testScope = TestScope(testScheduler)
            VitalsLayoutRepositoryImpl(dataStore, testScope)
            testScope.advanceUntilIdle()

            assertEquals(SettingsDefaults.DEFAULT_VITALS_CHARTS.size, persisted.trendChartsCount)

            val appendedCharts = persisted.trendChartsList.filter { it.chartId != VitalsChartId.HRV_TREND.name }
            appendedCharts.forEachIndexed { index, protoChart ->
                assertEquals(3 + index, protoChart.position)
            }
            assertEquals(1, persisted.trendChartsList.count { it.chartId == VitalsChartId.HRV_TREND.name })
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun init_doesNotDuplicateDefaultsOnRepeatedInit() =
        runTest {
            var persisted =
                VitalsLayoutConfigurationsProto
                    .newBuilder()
                    .addVitalsCards(cardProto(CardId.RESTING_HR.name, position = 0))
                    .addTrendCharts(chartProto(VitalsChartId.HRV_TREND.name, position = 0))
                    .build()
            coEvery { dataStore.updateData(any()) } coAnswers {
                val transform = firstArg<suspend (VitalsLayoutConfigurationsProto) -> VitalsLayoutConfigurationsProto>()
                persisted = transform(persisted)
                persisted
            }

            val firstScope = TestScope(testScheduler)
            VitalsLayoutRepositoryImpl(dataStore, firstScope)
            firstScope.advanceUntilIdle()
            assertEquals(SettingsDefaults.DEFAULT_VITALS_CARDS.size, persisted.vitalsCardsCount)
            assertEquals(SettingsDefaults.DEFAULT_VITALS_CHARTS.size, persisted.trendChartsCount)
            assertEquals(1, persisted.vitalsCardsList.count { it.cardId == CardId.RESTING_HR.name })
            assertEquals(1, persisted.trendChartsList.count { it.chartId == VitalsChartId.HRV_TREND.name })

            val secondScope = TestScope(testScheduler)
            VitalsLayoutRepositoryImpl(dataStore, secondScope)
            secondScope.advanceUntilIdle()

            assertEquals(SettingsDefaults.DEFAULT_VITALS_CARDS.size, persisted.vitalsCardsCount)
            assertEquals(SettingsDefaults.DEFAULT_VITALS_CHARTS.size, persisted.trendChartsCount)
            assertEquals(1, persisted.vitalsCardsList.count { it.cardId == CardId.HRV.name })
            assertEquals(1, persisted.trendChartsList.count { it.chartId == VitalsChartId.RHR_TREND.name })
        }

    @Test
    fun vitalsChartConfigurations_unknownChartIdIsDroppedAndDefaultsStillAppended() =
        runTest {
            val proto =
                VitalsLayoutConfigurationsProto
                    .newBuilder()
                    .addTrendCharts(chartProto("SOME_FUTURE_CHART"))
                    .addTrendCharts(chartProto(VitalsChartId.HRV_TREND.name, position = 1))
                    .build()

            every { dataStore.data } returns flowOf(proto)

            val result = repository.vitalsChartConfigurations().first()

            assertNotNull(result.find { it.chartId == VitalsChartId.HRV_TREND })
            assertEquals(SettingsDefaults.DEFAULT_VITALS_CHARTS.size, result.size)
        }

    @Test
    fun vitalsCardConfigurations_unknownCardIdIsDroppedAndDefaultsStillAppended() =
        runTest {
            val proto =
                VitalsLayoutConfigurationsProto
                    .newBuilder()
                    .addVitalsCards(cardProto("SOME_FUTURE_CARD_TYPE"))
                    .addVitalsCards(cardProto(CardId.HRV.name, position = 1))
                    .build()

            every { dataStore.data } returns flowOf(proto)

            val result = repository.vitalsCardConfigurations().first()

            assertNotNull(result.find { it.cardId == CardId.HRV })
            assertEquals(SettingsDefaults.DEFAULT_VITALS_CARDS.size, result.size)
        }
}
