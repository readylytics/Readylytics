package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.database.data.local.HealthDatabase
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
class HealthMutationCoordinatorImplTest {
    private lateinit var database: HealthDatabase
    private lateinit var coordinator: HealthMutationCoordinatorImpl

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        coordinator = HealthMutationCoordinatorImpl(database.healthMutationStateDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun nestedMutationInSameCoroutineCompletes() = runBlocking {
        withTimeout(1000) {
            assertEquals(42, coordinator.withMutation { coordinator.withMutation { 42 } })
        }
    }

    @Test
    fun childJobCannotBypassParentsMutationLock() = runBlocking {
        var entered = false
        coordinator.withMutation {
            val child = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withMutation { entered = true }
            }
            assertFalse(entered)
            child.cancelAndJoin()
        }
        assertEquals(42, coordinator.withMutation { 42 })
    }

    @Test
    fun mutationAndMaintenanceNeverOverlap() =
        runBlocking {
            val mutationEntered = CompletableDeferred<Unit>()
            val releaseMutation = CompletableDeferred<Unit>()
            val maintenanceEntered = AtomicBoolean(false)

            val mutation =
                async(Dispatchers.Default) {
                    coordinator.withMutation {
                        mutationEntered.complete(Unit)
                        releaseMutation.await()
                    }
                }
            mutationEntered.await()

            val maintenance =
                async(Dispatchers.Default) {
                    coordinator.withMaintenance("maintenance") {
                        maintenanceEntered.set(true)
                    }
                }
            Thread.sleep(25)
            assertFalse(maintenanceEntered.get())

            releaseMutation.complete(Unit)
            mutation.await()
            maintenance.await()
            assertTrue(maintenanceEntered.get())
            assertNull(database.healthMutationStateDao().current().maintenanceOperationId)
        }

    @Test
    fun cancellationReleasesMutationLock() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val mutation =
                async(Dispatchers.Default) {
                    coordinator.withMutation {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            entered.await()
            mutation.cancelAndJoin()

            assertEquals("ok", coordinator.withMutation { "ok" })
        }

    @Test
    fun failedMaintenanceLeavesMarkerForSameOperationToResume() =
        runBlocking {
            runCatching {
                coordinator.withMaintenance("restore-1") {
                    error("interrupted")
                }
            }

            assertEquals(
                "restore-1",
                database.healthMutationStateDao().current().maintenanceOperationId,
            )
            assertTrue(
                runCatching { coordinator.withMutation { Unit } }
                    .exceptionOrNull()
                    ?.message
                    ?.contains("MAINTENANCE_PENDING") == true,
            )
            assertTrue(
                runCatching {
                    coordinator.withMaintenance("other") { Unit }
                }.exceptionOrNull()?.message?.contains("MAINTENANCE_PENDING") == true,
            )

            coordinator.withMaintenance("restore-1") { 42 }
            assertNull(database.healthMutationStateDao().current().maintenanceOperationId)
        }
}
