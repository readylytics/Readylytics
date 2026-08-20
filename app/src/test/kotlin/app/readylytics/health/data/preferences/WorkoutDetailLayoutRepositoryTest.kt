package app.readylytics.health.data.preferences

import androidx.datastore.core.DataStore
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.domain.workouts.WorkoutDetailLayoutRepository
import app.readylytics.health.domain.workouts.detail.WorkoutDetailItemConfiguration
import app.readylytics.health.domain.workouts.detail.WorkoutDetailItemId
import app.readylytics.health.domain.workouts.detail.WorkoutLayoutType
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutDetailLayoutRepositoryTest {
    private val dataStore = mockk<DataStore<WorkoutDetailLayoutConfigurationsProto>>(relaxed = true)
    private lateinit var repository: WorkoutDetailLayoutRepository

    @Before
    fun setup() {
        repository = WorkoutDetailLayoutRepositoryImpl(dataStore)
    }

    private fun protoWith(
        type: WorkoutLayoutType,
        items: List<WorkoutDetailItemConfiguration>,
    ): WorkoutDetailLayoutConfigurationsProto =
        WorkoutDetailLayoutConfigurationsProto
            .newBuilder()
            .putLayoutsByType(
                type.name,
                WorkoutDetailLayoutProto
                    .newBuilder()
                    .addAllItems(items.map { WorkoutDetailLayoutMapper.toProto(it) })
                    .build(),
            ).build()

    @Test
    fun layoutFor_returnsDefaults_whenNothingStored() =
        runTest {
            coEvery { dataStore.data } returns flowOf(WorkoutDetailLayoutConfigurationsProto.getDefaultInstance())

            val result = repository.layoutFor(WorkoutLayoutType.RUNNING).first()

            assertEquals(SettingsDefaults.DEFAULT_WORKOUT_DETAIL_ITEMS, result)
        }

    @Test
    fun layoutFor_returnsStoredLayout_forThatTypeOnly() =
        runTest {
            val stored =
                SettingsDefaults.DEFAULT_WORKOUT_DETAIL_ITEMS.map {
                    if (it.itemId == WorkoutDetailItemId.RAS) it.copy(isVisible = false) else it
                }
            coEvery { dataStore.data } returns flowOf(protoWith(WorkoutLayoutType.RUNNING, stored))

            val running = repository.layoutFor(WorkoutLayoutType.RUNNING).first()
            val cycling = repository.layoutFor(WorkoutLayoutType.CYCLING).first()

            assertFalse(running.first { it.itemId == WorkoutDetailItemId.RAS }.isVisible)
            assertTrue(cycling.first { it.itemId == WorkoutDetailItemId.RAS }.isVisible)
        }

    @Test
    fun layoutFor_appendsItemsMissingFromStoredLayout() =
        runTest {
            val stored =
                SettingsDefaults.DEFAULT_WORKOUT_DETAIL_ITEMS.filter {
                    it.itemId !=
                        WorkoutDetailItemId.RECOVERY_HRR
                }
            coEvery { dataStore.data } returns flowOf(protoWith(WorkoutLayoutType.RUNNING, stored))

            val result = repository.layoutFor(WorkoutLayoutType.RUNNING).first()

            assertEquals(WorkoutDetailItemId.RECOVERY_HRR, result.last().itemId)
            assertEquals(WorkoutDetailItemId.entries.size, result.size)
        }

    @Test
    fun layoutFor_dropsUnknownItemIds() =
        runTest {
            val proto =
                WorkoutDetailLayoutConfigurationsProto
                    .newBuilder()
                    .putLayoutsByType(
                        WorkoutLayoutType.RUNNING.name,
                        WorkoutDetailLayoutProto
                            .newBuilder()
                            .addItems(
                                WorkoutDetailItemConfigurationProto
                                    .newBuilder()
                                    .setItemId("ITEM_FROM_THE_FUTURE")
                                    .setIsVisible(true)
                                    .setPosition(0)
                                    .build(),
                            ).build(),
                    ).build()
            coEvery { dataStore.data } returns flowOf(proto)

            val result = repository.layoutFor(WorkoutLayoutType.RUNNING).first()

            assertEquals(WorkoutDetailItemId.entries.size, result.size)
            assertTrue(result.none { it.itemId.name == "ITEM_FROM_THE_FUTURE" })
        }

    @Test
    fun allLayouts_dropsUnknownTypeKeys_andReturnsOnlyStoredTypes() =
        runTest {
            val proto =
                protoWith(WorkoutLayoutType.RUNNING, SettingsDefaults.DEFAULT_WORKOUT_DETAIL_ITEMS)
                    .toBuilder()
                    .putLayoutsByType(
                        "TYPE_FROM_THE_FUTURE",
                        WorkoutDetailLayoutProto.getDefaultInstance(),
                    ).build()
            coEvery { dataStore.data } returns flowOf(proto)

            val result = repository.allLayouts().first()

            assertEquals(setOf(WorkoutLayoutType.RUNNING), result.keys)
        }

    @Test
    fun updateLayout_writesOnlyThatType() =
        runTest {
            val transform =
                slot<
                    suspend (
                        WorkoutDetailLayoutConfigurationsProto,
                    ) -> WorkoutDetailLayoutConfigurationsProto,
                >()
            coEvery { dataStore.updateData(capture(transform)) } returns
                WorkoutDetailLayoutConfigurationsProto.getDefaultInstance()

            val items = SettingsDefaults.DEFAULT_WORKOUT_DETAIL_ITEMS.take(2)
            repository.updateLayout(WorkoutLayoutType.CYCLING, items)

            val existing = protoWith(WorkoutLayoutType.RUNNING, SettingsDefaults.DEFAULT_WORKOUT_DETAIL_ITEMS)
            val updated = transform.captured(existing)

            assertTrue(updated.containsLayoutsByType(WorkoutLayoutType.RUNNING.name))
            assertEquals(2, updated.getLayoutsByTypeOrThrow(WorkoutLayoutType.CYCLING.name).itemsCount)
        }

    @Test
    fun replaceAll_discardsPreviousLayouts() =
        runTest {
            val transform =
                slot<
                    suspend (
                        WorkoutDetailLayoutConfigurationsProto,
                    ) -> WorkoutDetailLayoutConfigurationsProto,
                >()
            coEvery { dataStore.updateData(capture(transform)) } returns
                WorkoutDetailLayoutConfigurationsProto.getDefaultInstance()

            repository.replaceAll(
                mapOf(WorkoutLayoutType.SWIMMING to SettingsDefaults.DEFAULT_WORKOUT_DETAIL_ITEMS),
            )

            val existing = protoWith(WorkoutLayoutType.RUNNING, SettingsDefaults.DEFAULT_WORKOUT_DETAIL_ITEMS)
            val updated = transform.captured(existing)

            assertEquals(setOf(WorkoutLayoutType.SWIMMING.name), updated.layoutsByTypeMap.keys)
        }

    @Test
    fun resetAll_clearsEveryStoredLayout() =
        runTest {
            val transform =
                slot<
                    suspend (
                        WorkoutDetailLayoutConfigurationsProto,
                    ) -> WorkoutDetailLayoutConfigurationsProto,
                >()
            coEvery { dataStore.updateData(capture(transform)) } returns
                WorkoutDetailLayoutConfigurationsProto.getDefaultInstance()

            repository.resetAll()

            val existing = protoWith(WorkoutLayoutType.RUNNING, SettingsDefaults.DEFAULT_WORKOUT_DETAIL_ITEMS)
            val updated = transform.captured(existing)

            assertTrue(updated.layoutsByTypeMap.isEmpty())
        }
}
