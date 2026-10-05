package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.response.ChangesResponse
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.sync.HealthChangeTokenStore
import app.readylytics.health.core.model.domain.sync.PreparedWorkout
import app.readylytics.health.core.model.domain.sync.WorkoutInput
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant

/**
 * Brief Step 1 TDD coverage for WP-14 page-batching: one Room transaction per page (not per
 * record), the [MAX_CHANGE_PAGES_PER_RUN] budget cap, and intra-page last-same-ID-event-wins.
 * Split out of [HealthChangeSynchronizerImplTest] (rather than added there) so neither file
 * crosses detekt's LargeClass threshold -- these three tests build their own
 * [HealthChangeSynchronizerImpl] per case (a counting [FakeStore], not the other file's mockk
 * stores) so they need only this file's own minimal fixture.
 */
class HealthChangeSynchronizerPageBatchingTest {
    private val tokenStore = mockk<HealthChangeTokenStore>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val workoutReadPreparer = mockk<WorkoutReadPreparer>(relaxed = true)
    private val client = mockk<HealthConnectClient>(relaxed = true)

    @Before
    fun setup() {
        val allReadPermissions =
            HealthDataType.entries.flatMap { dataType ->
                recordClassesFor(dataType).map {
                    androidx.health.connect.client.permission.HealthPermission.getReadPermission(it)
                }
            }.toSet()
        coEvery { client.permissionController.getGrantedPermissions() } returns allReadPermissions
        every { settingsRepo.userPreferences } returns
            flowOf(app.readylytics.health.core.model.data.preferences.UserPreferences())
        stubWorkoutReadPreparer()
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    private fun newSynchronizer(fake: FakeStore): HealthChangeSynchronizerImpl =
        HealthChangeSynchronizerImpl(
            client = client,
            tokenStore = tokenStore,
            settingsRepo = settingsRepo,
            healthIngestionStore = fake,
            changeIngestionStore = fake,
            transactionRunner = fake,
            workoutReadPreparer = workoutReadPreparer,
            clock = Clock.systemDefaultZone(),
        )

    /**
     * Real (non-mock) [PreparedWorkout] echoing [baseWorkout] back with every optional read
     * denied -- enough for `upsertExercises` to call `.copy()` on a genuine [WorkoutInput]
     * instead of chasing a deep-stubbed mock's fields.
     */
    private fun stubWorkoutReadPreparer() {
        coEvery { workoutReadPreparer.prepare(any(), any()) } answers {
            val baseWorkout = secondArg<WorkoutInput>()
            PreparedWorkout(
                workout = baseWorkout,
                route = ReadOutcome.Denied,
                distanceMeters = ReadOutcome.Denied,
                elevationMeters = ReadOutcome.Denied,
            )
        }
    }

    private fun exerciseRecord(recordId: String, deviceModel: String): ExerciseSessionRecord =
        mockk<ExerciseSessionRecord>(relaxed = true) {
            every { metadata } returns
                mockk<Metadata> {
                    every { id } returns recordId
                    every { device } returns
                        mockk<Device> {
                            every { model } returns deviceModel
                            every { manufacturer } returns null
                        }
                    every { dataOrigin } returns mockk<DataOrigin> { every { packageName } returns "pkg" }
                }
            every { startTime } returns Instant.parse("2026-06-19T10:00:00Z")
            every { endTime } returns Instant.parse("2026-06-19T11:00:00Z")
            every { exerciseType } returns ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
        }

    private fun changesResponse(
        changes: List<androidx.health.connect.client.changes.Change>,
        nextToken: String = "next-token",
        hasMore: Boolean = false,
    ) = mockk<ChangesResponse>(relaxed = true) {
        every { changesTokenExpired } returns false
        every { this@mockk.changes } returns changes
        every { nextChangesToken } returns nextToken
        every { this@mockk.hasMore } returns hasMore
    }

    /**
     * Stateful [tokenStore] stub: [get] reflects [tokens] (null for any type absent from it).
     * Since EXERCISE is first in [HealthDataType.entries], seeding only EXERCISE still exercises
     * exactly that type's page-batching before the loop hits the next type's missing token and
     * short-circuits with [HealthChangeSyncOutcome.fullResync].
     */
    private fun setupFakeTokenStore(tokens: Map<HealthDataType, String>) {
        coEvery { tokenStore.get(any()) } answers { tokens[firstArg<HealthDataType>()] }
    }

    @Test
    fun `thousandChangesUseOnePageTransaction`() = runTest {
        val fake = FakeStore()
        val synchronizer = newSynchronizer(fake)

        val changes = (1..1000).map { i -> UpsertionChange(record = exerciseRecord("id_$i", "Model")) }

        coEvery { client.getChanges("token_start") } returns changesResponse(changes, "token_end", false)
        setupFakeTokenStore(mapOf(HealthDataType.EXERCISE to "token_start"))

        synchronizer.applyPendingChanges()

        assertEquals(1, fake.transactionCount)
        assertEquals(1, fake.batchPersistCount)
        assertTrue(fake.affectedDateCalls <= 2)
        assertTrue(fake.deleteCalls <= 2)
    }

    @Test
    fun `pageBudgetPreservesCommittedPrefix`() = runTest {
        val fake = FakeStore()
        val synchronizer = newSynchronizer(fake)

        for (i in 1..21) {
            val changes = listOf(UpsertionChange(record = exerciseRecord("id_$i", "Model")))
            coEvery { client.getChanges("token_${i - 1}") } returns changesResponse(changes, "token_$i", hasMore = true)
        }
        setupFakeTokenStore(mapOf(HealthDataType.EXERCISE to "token_0"))

        val outcome = synchronizer.applyPendingChanges()

        assertEquals(20, fake.pagesApplied)
        assertEquals("token_20", outcome.nextTokens[HealthDataType.EXERCISE])
        assertTrue(outcome.continuationRequired)
        assertTrue(outcome.requiresFullResync)
    }

    @Test
    fun `last same-ID event in a page wins`() = runTest {
        val fake = FakeStore()
        val synchronizer = newSynchronizer(fake)

        // Same ID upserted, then deleted, in the same page -- the later DeletionChange must win:
        // the record must end up deleted, never resurrected by the earlier upsert.
        val changes =
            listOf(
                UpsertionChange(record = exerciseRecord("dup-id", "Model")),
                DeletionChange(recordId = "dup-id"),
            )
        coEvery { client.getChanges("token_dup") } returns changesResponse(changes, "token_dup_end", false)
        setupFakeTokenStore(mapOf(HealthDataType.EXERCISE to "token_dup"))

        synchronizer.applyPendingChanges()

        assertEquals(0, fake.batchPersistCount)
        assertEquals(1, fake.deleteCalls)
    }
}
