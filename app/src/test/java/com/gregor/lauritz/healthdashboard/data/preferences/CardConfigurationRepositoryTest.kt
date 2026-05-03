package com.gregor.lauritz.healthdashboard.data.preferences

import androidx.datastore.core.DataStore
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId
import com.gregor.lauritz.healthdashboard.domain.dashboard.ScreenType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CardConfigurationRepositoryTest {
    private val dataStore = mockk<DataStore<CardConfigurationsProto>>(relaxed = true)
    private lateinit var repository: CardConfigurationRepository

    @Before
    fun setup() {
        repository = CardConfigurationRepository(dataStore)
    }

    @Test
    fun dashboardCardConfigurations_returnsMappedDomainModels() = runTest {
        val proto = CardConfigurationsProto.newBuilder()
            .addDashboardCards(CardConfigurationProto.newBuilder()
                .setCardId(CardId.SLEEP_SCORE.name)
                .setIsVisible(true)
                .setPosition(0)
                .build())
            .build()
        
        every { dataStore.data } returns flowOf(proto)

        val result = repository.dashboardCardConfigurations().first()
        
        assertEquals(1, result.size)
        assertEquals(CardId.SLEEP_SCORE, result[0].cardId)
        assertTrue(result[0].isVisible)
        assertEquals(0, result[0].position)
    }

    @Test
    fun updateCardConfigurations_updatesCorrectProtoField() = runTest {
        val capturedUpdate = slot<suspend (CardConfigurationsProto) -> CardConfigurationsProto>()
        coEvery { dataStore.updateData(capture(capturedUpdate)) } returns CardConfigurationsProto.getDefaultInstance()

        val newConfigs = listOf(
            com.gregor.lauritz.healthdashboard.domain.dashboard.CardConfiguration(CardId.READINESS, isVisible = true, position = 0)
        )

        repository.updateCardConfigurations(ScreenType.DASHBOARD, newConfigs)

        val initialProto = CardConfigurationsProto.getDefaultInstance()
        val updatedProto = capturedUpdate.captured(initialProto)

        assertEquals(1, updatedProto.dashboardCardsCount)
        assertEquals(CardId.READINESS.name, updatedProto.getDashboardCards(0).cardId)
    }
}
