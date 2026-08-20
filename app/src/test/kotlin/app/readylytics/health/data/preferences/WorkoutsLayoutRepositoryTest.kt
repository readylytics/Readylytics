package app.readylytics.health.data.preferences

import androidx.datastore.core.DataStore
import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.workouts.WorkoutChartId
import app.readylytics.health.domain.workouts.WorkoutHistoryId
import app.readylytics.health.domain.workouts.WorkoutsLayoutRepository
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutsLayoutRepositoryTest {
    private val dataStore = mockk<DataStore<WorkoutsLayoutConfigurationsProto>>(relaxed = true)
    private lateinit var repository: WorkoutsLayoutRepository

    @Before
    fun setup() {
        repository = WorkoutsLayoutRepositoryImpl(dataStore, TestScope())
    }

    @Test
    fun workoutCardConfigurations_returnsMappedDomainModels() =
        runTest {
            val proto =
                WorkoutsLayoutConfigurationsProto
                    .newBuilder()
                    .addWorkoutCards(
                        WorkoutCardConfigurationProto
                            .newBuilder()
                            .setCardId(CardId.STRAIN_RATIO.name)
                            .setIsVisible(true)
                            .setPosition(0)
                            .setRequestedDisplayMode(DashboardCardDisplayMode.GAUGE.name)
                            .build(),
                    ).build()

            every { dataStore.data } returns flowOf(proto)

            val result = repository.workoutCardConfigurations().first()

            val strainCard = result.find { it.cardId == CardId.STRAIN_RATIO }
            assertNotNull(strainCard)
            assertTrue(strainCard.isVisible)
            assertEquals(DashboardCardDisplayMode.GAUGE, strainCard.requestedDisplayMode)
        }

    @Test
    fun workoutChartConfigurations_returnsMappedDomainModels() =
        runTest {
            val proto =
                WorkoutsLayoutConfigurationsProto
                    .newBuilder()
                    .addWorkoutCharts(
                        WorkoutChartConfigurationProto
                            .newBuilder()
                            .setChartId(WorkoutChartId.ACWR_TRIMP.name)
                            .setIsVisible(true)
                            .setPosition(0)
                            .build(),
                    ).build()

            every { dataStore.data } returns flowOf(proto)

            val result = repository.workoutChartConfigurations().first()

            assertNotNull(result.find { it.chartId == WorkoutChartId.ACWR_TRIMP })
        }

    @Test
    fun workoutHistoryConfigurations_returnsMappedDomainModels() =
        runTest {
            val proto =
                WorkoutsLayoutConfigurationsProto
                    .newBuilder()
                    .addWorkoutHistory(
                        WorkoutHistoryConfigurationProto
                            .newBuilder()
                            .setHistoryId(WorkoutHistoryId.WORKOUT_LIST.name)
                            .setIsVisible(true)
                            .setPosition(0)
                            .build(),
                    ).build()

            every { dataStore.data } returns flowOf(proto)

            val result = repository.workoutHistoryConfigurations().first()

            assertNotNull(result.find { it.historyId == WorkoutHistoryId.WORKOUT_LIST })
        }

    @Test
    fun updateWorkoutCardConfigurations_writesCorrectProtoField() =
        runTest {
            val capturedUpdate =
                slot<
                    suspend (
                        WorkoutsLayoutConfigurationsProto,
                    ) -> WorkoutsLayoutConfigurationsProto,
                >()
            coEvery { dataStore.updateData(capture(capturedUpdate)) } returns
                WorkoutsLayoutConfigurationsProto.getDefaultInstance()

            val newConfigs =
                listOf(
                    CardConfiguration(
                        CardId.READINESS,
                        isVisible = false,
                        position = 1,
                        requestedDisplayMode = DashboardCardDisplayMode.VALUE,
                    ),
                )

            repository.updateWorkoutCardConfigurations(newConfigs)

            val updatedProto = capturedUpdate.captured(WorkoutsLayoutConfigurationsProto.getDefaultInstance())
            assertEquals(1, updatedProto.workoutCardsCount)
            assertEquals(CardId.READINESS.name, updatedProto.getWorkoutCards(0).cardId)
            assertFalse(updatedProto.getWorkoutCards(0).isVisible)
        }

    @Test
    fun init_appendsMissingDefaultCardsChartsAndHistoryOnceAndRenumbersPositions() =
        runTest {
            var persisted =
                WorkoutsLayoutConfigurationsProto
                    .newBuilder()
                    .addWorkoutCards(
                        WorkoutCardConfigurationProto
                            .newBuilder()
                            .setCardId(CardId.STRAIN_RATIO.name)
                            .setPosition(0)
                            .build(),
                    ).build()
            coEvery { dataStore.updateData(any()) } coAnswers {
                val transform =
                    firstArg<
                        suspend (
                            WorkoutsLayoutConfigurationsProto,
                        ) -> WorkoutsLayoutConfigurationsProto,
                    >()
                persisted = transform(persisted)
                persisted
            }

            val testScope = TestScope(testScheduler)
            WorkoutsLayoutRepositoryImpl(dataStore, testScope)
            testScope.advanceUntilIdle()

            assertEquals(SettingsDefaults.DEFAULT_WORKOUT_CARDS.size, persisted.workoutCardsCount)
            assertEquals(SettingsDefaults.DEFAULT_WORKOUT_CHARTS.size, persisted.workoutChartsCount)
            assertEquals(SettingsDefaults.DEFAULT_WORKOUT_HISTORY.size, persisted.workoutHistoryCount)
        }
}
