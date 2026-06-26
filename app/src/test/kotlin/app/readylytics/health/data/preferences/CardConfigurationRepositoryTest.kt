package app.readylytics.health.data.preferences

import androidx.datastore.core.DataStore
import app.readylytics.health.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.domain.dashboard.CardId
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
import kotlin.test.assertTrue

class CardConfigurationRepositoryTest {
    private val dataStore = mockk<DataStore<CardConfigurationsProto>>(relaxed = true)
    private lateinit var repository: CardConfigurationRepository

    @Before
    fun setup() {
        repository = CardConfigurationRepositoryImpl(dataStore, TestScope())
    }

    @Test
    fun dashboardCardConfigurations_returnsMappedDomainModels() =
        runTest {
            val proto =
                CardConfigurationsProto
                    .newBuilder()
                    .addDashboardCards(
                        CardConfigurationProto
                            .newBuilder()
                            .setCardId(CardId.SLEEP_SCORE.name)
                            .setIsVisible(true)
                            .setPosition(0)
                            .build(),
                    ).build()

            every { dataStore.data } returns flowOf(proto)

            val result = repository.dashboardCardConfigurations().first()

            val sleepScoreCard = result.find { it.cardId == CardId.SLEEP_SCORE }
            assertNotNull(sleepScoreCard)
            assertEquals(CardId.SLEEP_SCORE, sleepScoreCard.cardId)
            assertTrue(sleepScoreCard.isVisible)
            assertEquals(0, sleepScoreCard.position)
        }

    @Test
    fun updateDashboardCardConfigurations_updatesCorrectProtoField() =
        runTest {
            val capturedUpdate = slot<suspend (CardConfigurationsProto) -> CardConfigurationsProto>()
            coEvery { dataStore.updateData(capture(capturedUpdate)) } returns
                CardConfigurationsProto.getDefaultInstance()

            val newConfigs =
                listOf(
                    app.readylytics.health.domain.dashboard.CardConfiguration(
                        CardId.READINESS,
                        isVisible = true,
                        position = 0,
                    ),
                )

            repository.updateDashboardCardConfigurations(newConfigs)

            val initialProto = CardConfigurationsProto.getDefaultInstance()
            val updatedProto = capturedUpdate.captured(initialProto)

            assertEquals(1, updatedProto.dashboardCardsCount)
            assertEquals(CardId.READINESS.name, updatedProto.getDashboardCards(0).cardId)
        }

    @Test
    fun defaultDashboardCards_includeSingleInsightsSlot() {
        val insightCards =
            SettingsDefaults.DEFAULT_DASHBOARD_CARDS
                .filter { it.cardId.name.contains("INSIGHT") }

        assertEquals(listOf(CardId.INSIGHTS), insightCards.map { it.cardId })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun init_appendsMissingDefaultCardsToExistingConfigurations() =
        runTest {
            val capturedUpdate = slot<suspend (CardConfigurationsProto) -> CardConfigurationsProto>()
            coEvery { dataStore.updateData(capture(capturedUpdate)) } returns
                CardConfigurationsProto.getDefaultInstance()

            val existingProto =
                CardConfigurationsProto
                    .newBuilder()
                    .addDashboardCards(
                        CardConfigurationProto
                            .newBuilder()
                            .setCardId(CardId.SLEEP_SCORE.name)
                            .setIsVisible(true)
                            .setPosition(0)
                            .build(),
                    ).build()

            // Recreate repository to trigger init block with test scope
            val testScope = TestScope(testScheduler)
            val repo = CardConfigurationRepositoryImpl(dataStore, testScope)
            testScope.advanceUntilIdle()

            val updatedProto = capturedUpdate.captured(existingProto)

            assertEquals(SettingsDefaults.DEFAULT_DASHBOARD_CARDS.size, updatedProto.dashboardCardsCount)

            val appendedCards = updatedProto.dashboardCardsList.filter { it.cardId != CardId.SLEEP_SCORE.name }
            appendedCards.forEachIndexed { index, protoCard ->
                assertEquals(1 + index, protoCard.position)
            }
        }
}
