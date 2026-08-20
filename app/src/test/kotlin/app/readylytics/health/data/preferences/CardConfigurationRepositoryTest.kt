package app.readylytics.health.data.preferences

import androidx.datastore.core.DataStore
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.DashboardCardCatalog
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
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

class CardConfigurationRepositoryTest {
    private val dataStore = mockk<DataStore<CardConfigurationsProto>>(relaxed = true)
    private lateinit var repository: CardConfigurationRepository

    @Before
    fun setup() {
        repository = CardConfigurationRepositoryImpl(dataStore, TestScope())
    }

    private fun cardProto(
        cardId: String,
        isVisible: Boolean = true,
        position: Int = 0,
        requestedDisplayMode: String? = null,
    ): CardConfigurationProto {
        val builder =
            CardConfigurationProto
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
    fun dashboardCardConfigurations_mapsRequestedDisplayModeWhenPresent() =
        runTest {
            val proto =
                CardConfigurationsProto
                    .newBuilder()
                    .addDashboardCards(
                        CardConfigurationProto
                            .newBuilder()
                            .setCardId(CardId.HRV.name)
                            .setIsVisible(true)
                            .setPosition(0)
                            .setRequestedDisplayMode(DashboardCardDisplayMode.BAR.name)
                            .build(),
                    ).build()

            every { dataStore.data } returns flowOf(proto)

            val result = repository.dashboardCardConfigurations().first()

            val hrvCard = result.find { it.cardId == CardId.HRV }
            assertNotNull(hrvCard)
            assertEquals(DashboardCardDisplayMode.BAR, hrvCard.requestedDisplayMode)
        }

    @Test
    fun dashboardCardConfigurations_mapsMissingRequestedDisplayModeToNull() =
        runTest {
            val proto =
                CardConfigurationsProto
                    .newBuilder()
                    .addDashboardCards(
                        CardConfigurationProto
                            .newBuilder()
                            .setCardId(CardId.HRV.name)
                            .setIsVisible(true)
                            .setPosition(0)
                            .build(),
                    ).build()

            every { dataStore.data } returns flowOf(proto)

            val result = repository.dashboardCardConfigurations().first()

            val hrvCard = result.find { it.cardId == CardId.HRV }
            assertNotNull(hrvCard)
            assertEquals(null, hrvCard.requestedDisplayMode)
        }

    @Test
    fun updateDashboardCardConfigurations_updatesCorrectProtoField() =
        runTest {
            val capturedUpdate = slot<suspend (CardConfigurationsProto) -> CardConfigurationsProto>()
            coEvery { dataStore.updateData(capture(capturedUpdate)) } returns
                CardConfigurationsProto.getDefaultInstance()

            val newConfigs =
                listOf(
                    app.readylytics.health.core.model.domain.dashboard.CardConfiguration(
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
    @OptIn(ExperimentalCoroutinesApi::class)
    fun init_appendsAiRecommendationOnceVisiblyAtEnd() =
        runTest {
            val capturedUpdate = slot<suspend (CardConfigurationsProto) -> CardConfigurationsProto>()
            coEvery { dataStore.updateData(capture(capturedUpdate)) } returns
                CardConfigurationsProto.getDefaultInstance()

            val existingProto =
                CardConfigurationsProto
                    .newBuilder()
                    .addDashboardCards(cardProto(CardId.SLEEP_SCORE.name, position = 4))
                    .build()

            val testScope = TestScope(testScheduler)
            CardConfigurationRepositoryImpl(dataStore, testScope)
            testScope.advanceUntilIdle()

            val updatedProto = capturedUpdate.captured(existingProto)

            val aiCards = updatedProto.dashboardCardsList.filter { it.cardId == CardId.AI_RECOMMENDATION.name }
            assertEquals(1, aiCards.size)
            assertTrue(aiCards.single().isVisible)
            assertEquals(CardId.AI_RECOMMENDATION.name, updatedProto.dashboardCardsList.last().cardId)
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun init_appendDoesNotDuplicateAiRecommendationOnRepeatedInit() =
        runTest {
            val capturedUpdate = slot<suspend (CardConfigurationsProto) -> CardConfigurationsProto>()
            coEvery { dataStore.updateData(capture(capturedUpdate)) } returns
                CardConfigurationsProto.getDefaultInstance()

            // First init appends all missing defaults, including AI_RECOMMENDATION.
            val firstProto =
                CardConfigurationsProto
                    .newBuilder()
                    .addDashboardCards(cardProto(CardId.SLEEP_SCORE.name, position = 0))
                    .build()
            val testScope = TestScope(testScheduler)
            CardConfigurationRepositoryImpl(dataStore, testScope)
            testScope.advanceUntilIdle()
            val afterFirst = capturedUpdate.captured(firstProto)
            assertEquals(1, afterFirst.dashboardCardsList.count { it.cardId == CardId.AI_RECOMMENDATION.name })

            // Second init sees the appended card already present, so it appends nothing.
            val secondScope = TestScope(testScheduler)
            CardConfigurationRepositoryImpl(dataStore, secondScope)
            secondScope.advanceUntilIdle()
            val afterSecond = capturedUpdate.captured(afterFirst)
            assertEquals(
                afterFirst.dashboardCardsCount,
                afterSecond.dashboardCardsCount,
            )
            assertEquals(1, afterSecond.dashboardCardsList.count { it.cardId == CardId.AI_RECOMMENDATION.name })
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

    @Test
    fun dashboardCardConfigurations_unknownStoredModeStringFallsBackToCatalogLegacyDefault() =
        runTest {
            // A raw stored string that does not parse to any DashboardCardDisplayMode enum entry
            // (e.g. corrupted data, or a mode name from a future app version rolled back).
            val proto =
                CardConfigurationsProto
                    .newBuilder()
                    .addDashboardCards(cardProto(CardId.HRV.name, requestedDisplayMode = "NOT_A_REAL_MODE"))
                    .build()

            every { dataStore.data } returns flowOf(proto)

            // Storage read succeeds (no exception) despite the unparseable value.
            val result = repository.dashboardCardConfigurations().first()

            val hrvCard = result.find { it.cardId == CardId.HRV }
            assertNotNull(hrvCard)
            assertNull(hrvCard.requestedDisplayMode)

            // With no explicit mode, the catalog falls back to the card's legacy default.
            val spec = DashboardCardCatalog.spec(CardId.HRV)
            assertNotNull(spec)
            assertEquals(spec.legacyDefaultMode, DashboardCardCatalog.requestedMode(hrvCard))
        }

    @Test
    fun dashboardCardConfigurations_knownButUnsupportedModeIsPreservedWhileCatalogRendersDefault() =
        runTest {
            // VALUE is a real DashboardCardDisplayMode entry, but STEPS' catalog spec only
            // supports BAR. This differs from an unparseable string: the stored value is a
            // legitimate enum member, so the mapper preserves it verbatim.
            val proto =
                CardConfigurationsProto
                    .newBuilder()
                    .addDashboardCards(
                        cardProto(
                            CardId.STEPS.name,
                            requestedDisplayMode = DashboardCardDisplayMode.VALUE.name,
                        ),
                    ).build()

            every { dataStore.data } returns flowOf(proto)

            val result = repository.dashboardCardConfigurations().first()

            val stepsCard = result.find { it.cardId == CardId.STEPS }
            assertNotNull(stepsCard)
            // The requested mode itself is preserved as stored, not silently cleared.
            assertEquals(DashboardCardDisplayMode.VALUE, stepsCard.requestedDisplayMode)

            // But since VALUE is not in STEPS' supported modes, the catalog resolves to its
            // legacy default (BAR) for rendering purposes.
            val spec = DashboardCardCatalog.spec(CardId.STEPS)
            assertNotNull(spec)
            assertEquals(spec.legacyDefaultMode, DashboardCardCatalog.requestedMode(stepsCard))
        }

    @Test
    fun dashboardCardConfigurations_reloadRetainsIndependentExplicitModesPerCard() =
        runTest {
            val hrvBar = cardProto(CardId.HRV.name, requestedDisplayMode = DashboardCardDisplayMode.BAR.name)
            val restingHrGauge =
                cardProto(
                    CardId.RESTING_HR.name,
                    position = 1,
                    requestedDisplayMode = DashboardCardDisplayMode.GAUGE.name,
                )
            val firstProto =
                CardConfigurationsProto
                    .newBuilder()
                    .addDashboardCards(hrvBar)
                    .addDashboardCards(restingHrGauge)
                    .build()

            every { dataStore.data } returns flowOf(firstProto)
            val firstResult = repository.dashboardCardConfigurations().first()
            val firstHrv = firstResult.find { it.cardId == CardId.HRV }
            val firstRestingHr = firstResult.find { it.cardId == CardId.RESTING_HR }
            assertEquals(DashboardCardDisplayMode.BAR, firstHrv?.requestedDisplayMode)
            assertEquals(DashboardCardDisplayMode.GAUGE, firstRestingHr?.requestedDisplayMode)

            // Simulate a DataStore reload where only one card's explicit mode changed. The
            // other card's independently stored mode must be unaffected.
            val hrvValue = cardProto(CardId.HRV.name, requestedDisplayMode = DashboardCardDisplayMode.VALUE.name)
            val secondProto =
                CardConfigurationsProto
                    .newBuilder()
                    .addDashboardCards(hrvValue)
                    .addDashboardCards(restingHrGauge)
                    .build()

            every { dataStore.data } returns flowOf(secondProto)
            val secondResult = repository.dashboardCardConfigurations().first()
            val secondHrv = secondResult.find { it.cardId == CardId.HRV }
            val secondRestingHr = secondResult.find { it.cardId == CardId.RESTING_HR }
            assertEquals(DashboardCardDisplayMode.VALUE, secondHrv?.requestedDisplayMode)
            assertEquals(DashboardCardDisplayMode.GAUGE, secondRestingHr?.requestedDisplayMode)
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun init_appendPreservesExistingExplicitModesAndAppendsCardsWithNullMode() =
        runTest {
            val capturedUpdate = slot<suspend (CardConfigurationsProto) -> CardConfigurationsProto>()
            coEvery { dataStore.updateData(capture(capturedUpdate)) } returns
                CardConfigurationsProto.getDefaultInstance()

            val sleepScoreBar =
                cardProto(CardId.SLEEP_SCORE.name, requestedDisplayMode = DashboardCardDisplayMode.BAR.name)
            val hrvValue =
                cardProto(CardId.HRV.name, position = 1, requestedDisplayMode = DashboardCardDisplayMode.VALUE.name)
            val existingProto =
                CardConfigurationsProto
                    .newBuilder()
                    .addDashboardCards(sleepScoreBar)
                    .addDashboardCards(hrvValue)
                    .build()

            val testScope = TestScope(testScheduler)
            CardConfigurationRepositoryImpl(dataStore, testScope)
            testScope.advanceUntilIdle()

            val updatedProto = capturedUpdate.captured(existingProto)

            // Both pre-existing cards keep their own explicit mode through the append.
            val sleepScoreProto = updatedProto.dashboardCardsList.find { it.cardId == CardId.SLEEP_SCORE.name }
            assertNotNull(sleepScoreProto)
            assertEquals(DashboardCardDisplayMode.BAR.name, sleepScoreProto.requestedDisplayMode)

            val hrvProto = updatedProto.dashboardCardsList.find { it.cardId == CardId.HRV.name }
            assertNotNull(hrvProto)
            assertEquals(DashboardCardDisplayMode.VALUE.name, hrvProto.requestedDisplayMode)

            // Newly appended default cards have no explicit requested mode.
            val appendedCards =
                updatedProto.dashboardCardsList.filter {
                    it.cardId != CardId.SLEEP_SCORE.name && it.cardId != CardId.HRV.name
                }
            assertTrue(appendedCards.isNotEmpty())
            appendedCards.forEach { assertEquals("", it.requestedDisplayMode) }
        }

    @Test
    fun dashboardCardConfigurations_sparseKnownCardEntryStillSurfacesInOutput() =
        runTest {
            // A known CardId with no other fields set (no explicit mode, default
            // visibility/position) must still appear in repository output rather than
            // being dropped.
            val proto =
                CardConfigurationsProto
                    .newBuilder()
                    .addDashboardCards(CardConfigurationProto.newBuilder().setCardId(CardId.WEIGHT.name).build())
                    .build()

            every { dataStore.data } returns flowOf(proto)

            val result = repository.dashboardCardConfigurations().first()

            val weightCard = result.find { it.cardId == CardId.WEIGHT }
            assertNotNull(weightCard)
            assertNull(weightCard.requestedDisplayMode)
        }

    @Test
    fun dashboardCardConfigurations_unknownCardIdRetainsCurrentDroppedEntryBehavior() =
        runTest {
            val proto =
                CardConfigurationsProto
                    .newBuilder()
                    .addDashboardCards(cardProto("SOME_FUTURE_CARD_TYPE"))
                    .addDashboardCards(cardProto(CardId.HRV.name, position = 1))
                    .build()

            every { dataStore.data } returns flowOf(proto)

            // Storage read succeeds without throwing despite the unparseable card id.
            val result = repository.dashboardCardConfigurations().first()

            // The unknown entry is silently dropped rather than surfacing under any CardId;
            // the stored HRV entry survives, and missing catalog defaults still get appended
            // around it exactly once (no duplicate/leftover row for the dropped entry).
            assertNotNull(result.find { it.cardId == CardId.HRV })
            assertEquals(SettingsDefaults.DEFAULT_DASHBOARD_CARDS.size, result.size)
        }
}
