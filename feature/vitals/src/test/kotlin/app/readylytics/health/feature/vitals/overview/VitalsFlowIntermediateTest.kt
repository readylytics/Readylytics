package app.readylytics.health.feature.vitals.overview

import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.CardManagementDelegate
import app.readylytics.health.core.model.domain.vitals.VitalsLayoutRepository
import app.readylytics.health.domain.repository.HealthConnectRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VitalsFlowIntermediateTest {
    private val vitalsCardConfigs =
        listOf(
            CardConfiguration(CardId.RESTING_HR, isVisible = true, position = 0),
            CardConfiguration(CardId.HRV, isVisible = true, position = 1),
            CardConfiguration(CardId.OXYGEN_SATURATION, isVisible = true, position = 2),
            CardConfiguration(CardId.BODY_TEMPERATURE, isVisible = true, position = 3),
        )

    @Test
    fun `bodyTempPermissionDenied_filtersBodyTemperatureFromCommittedAndPending`() =
        runTest {
            val result =
                createVitalsCardStateFlow(
                    cardManagementDelegate =
                        mockCardManagementDelegate(
                            initialPendingConfigs = vitalsCardConfigs,
                        ),
                    vitalsLayoutRepository = mockVitalsLayoutRepository(),
                    healthConnectRepository =
                        mockk<HealthConnectRepository> {
                            coEvery { hasBodyTemperaturePermission() } returns false
                            coEvery { hasOxygenSaturationPermission() } returns true
                        },
                ).first()

            assertTrue(result.cardConfigurations.none { it.cardId == CardId.BODY_TEMPERATURE })
            assertTrue(result.cardConfigurations.any { it.cardId == CardId.OXYGEN_SATURATION })
            assertTrue(result.pendingConfiguration.orEmpty().none { it.cardId == CardId.BODY_TEMPERATURE })
            assertTrue(result.pendingConfiguration.orEmpty().any { it.cardId == CardId.OXYGEN_SATURATION })
        }

    @Test
    fun `spo2PermissionDenied_filtersOxygenSaturationFromCommittedAndPending`() =
        runTest {
            val result =
                createVitalsCardStateFlow(
                    cardManagementDelegate =
                        mockCardManagementDelegate(
                            initialPendingConfigs = vitalsCardConfigs,
                        ),
                    vitalsLayoutRepository = mockVitalsLayoutRepository(),
                    healthConnectRepository =
                        mockk<HealthConnectRepository> {
                            coEvery { hasBodyTemperaturePermission() } returns true
                            coEvery { hasOxygenSaturationPermission() } returns false
                        },
                ).first()

            assertTrue(result.cardConfigurations.none { it.cardId == CardId.OXYGEN_SATURATION })
            assertTrue(result.cardConfigurations.any { it.cardId == CardId.BODY_TEMPERATURE })
            assertTrue(result.pendingConfiguration.orEmpty().none { it.cardId == CardId.OXYGEN_SATURATION })
            assertTrue(result.pendingConfiguration.orEmpty().any { it.cardId == CardId.BODY_TEMPERATURE })
        }

    @Test
    fun `bothPermissionsDenied_filtersBothCards`() =
        runTest {
            val result =
                createVitalsCardStateFlow(
                    cardManagementDelegate =
                        mockCardManagementDelegate(
                            initialPendingConfigs = vitalsCardConfigs,
                        ),
                    vitalsLayoutRepository = mockVitalsLayoutRepository(),
                    healthConnectRepository =
                        mockk<HealthConnectRepository> {
                            coEvery { hasBodyTemperaturePermission() } returns false
                            coEvery { hasOxygenSaturationPermission() } returns false
                        },
                ).first()

            assertTrue(result.cardConfigurations.none { it.cardId == CardId.BODY_TEMPERATURE })
            assertTrue(result.cardConfigurations.none { it.cardId == CardId.OXYGEN_SATURATION })
            assertTrue(result.cardConfigurations.any { it.cardId == CardId.RESTING_HR })
            assertTrue(result.cardConfigurations.any { it.cardId == CardId.HRV })
        }

    @Test
    fun `permissionDenied_whileEditing_pendingListStillFiltered`() =
        runTest {
            val result =
                createVitalsCardStateFlow(
                    cardManagementDelegate =
                        mockCardManagementDelegate(
                            isManaging = true,
                            initialPendingConfigs = vitalsCardConfigs,
                        ),
                    vitalsLayoutRepository = mockVitalsLayoutRepository(),
                    healthConnectRepository =
                        mockk<HealthConnectRepository> {
                            coEvery { hasBodyTemperaturePermission() } returns false
                            coEvery { hasOxygenSaturationPermission() } returns false
                        },
                ).first()

            assertTrue(result.isManagingCards)
            assertTrue(result.pendingConfiguration.orEmpty().none { it.cardId == CardId.BODY_TEMPERATURE })
            assertTrue(result.pendingConfiguration.orEmpty().none { it.cardId == CardId.OXYGEN_SATURATION })
            assertTrue(result.pendingConfiguration.orEmpty().any { it.cardId == CardId.RESTING_HR })
            assertTrue(result.pendingConfiguration.orEmpty().any { it.cardId == CardId.HRV })
        }

    private fun mockCardManagementDelegate(
        isManaging: Boolean = false,
        initialPendingConfigs: List<CardConfiguration>? = null,
    ): CardManagementDelegate =
        mockk<CardManagementDelegate> {
            every { isManagingCards } returns MutableStateFlow(isManaging)
            every { pendingConfigs } returns MutableStateFlow(initialPendingConfigs)
        }

    private fun mockVitalsLayoutRepository(
        configs: List<CardConfiguration> = vitalsCardConfigs,
    ): VitalsLayoutRepository =
        mockk<VitalsLayoutRepository> {
            every { vitalsCardConfigurations() } returns flowOf(configs)
        }
}
