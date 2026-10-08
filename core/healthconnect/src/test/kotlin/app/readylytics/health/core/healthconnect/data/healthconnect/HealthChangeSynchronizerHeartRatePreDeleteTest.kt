package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.response.ChangesResponse
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.model.DomainHeartRateSample
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.model.RecordType
import app.readylytics.health.core.model.domain.model.WorkoutRoutePoint
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.HealthChangeIngestionStore
import app.readylytics.health.core.model.domain.sync.HealthChangeTokenStore
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.PreparedWorkout
import app.readylytics.health.core.model.domain.sync.SessionSpans
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * Changes-path contract for kept HEART_RATE/HRV upsertions: replaced in place via the source-scoped
 * writer, never pre-deleted (a pre-delete would journal a second dirty ticket). Split out of
 * [HealthChangeSynchronizerRecordSyncTest] so neither class crosses detekt's LargeClass threshold;
 * the fixture is duplicated because JUnit doesn't share `@Before` state across classes.
 */
class HealthChangeSynchronizerHeartRatePreDeleteTest {
    private val tokenStore = mockk<HealthChangeTokenStore>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val transactionRunner = mockk<TransactionRunner>(relaxed = true)
    private val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)
    private val changeIngestionStore = mockk<HealthChangeIngestionStore>(relaxed = true)
    private val workoutReadPreparer = mockk<WorkoutReadPreparer>()

    private val client = mockk<HealthConnectClient>(relaxed = true)

    /** Set to true only while inside [transactionRunner]'s block, false otherwise. */
    private var transactionActive = false

    private lateinit var synchronizer: HealthChangeSynchronizerImpl

    @Before
    fun setup() {
        coEvery { transactionRunner.runInTransaction<Any>(any()) } coAnswers {
            transactionActive = true
            try {
                firstArg<suspend () -> Any>().invoke()
            } finally {
                transactionActive = false
            }
        }

        coEvery { client.readRecords<Record>(any()) } returns
            mockk {
                every { records } returns emptyList()
                every { pageToken } returns null
            }

        coEvery { client.permissionController.getGrantedPermissions() } returns
            HealthDataType.entries.flatMap { current ->
                recordClassesFor(current).map {
                    HealthPermission.getReadPermission(it)
                }
            }.toSet()

        every { settingsRepo.userPreferences } returns flowOf(UserPreferences())

        coEvery { changeIngestionStore.sessionSpansOverlapping(any(), any()) } returns
            SessionSpans(emptyList(), emptyList())
        coEvery { changeIngestionStore.heartRateSamplesForMetrics(any(), any(), any()) } returns emptyList()

        // Default: no enrichment attempted, base workout passed through unchanged. Individual
        // tests override this to assert on route/distance/elevation propagation or transaction
        // timing.
        coEvery { workoutReadPreparer.prepare(any(), any()) } coAnswers {
            PreparedWorkout(
                workout = secondArg(),
                route = ReadOutcome.Denied,
                distanceMeters = ReadOutcome.Denied,
                elevationMeters = ReadOutcome.Denied,
            )
        }

        synchronizer =
            HealthChangeSynchronizerImpl(
                client = client,
                tokenStore = tokenStore,
                settingsRepo = settingsRepo,
                transactionRunner = transactionRunner,
                healthIngestionStore = healthIngestionStore,
                changeIngestionStore = changeIngestionStore,
                workoutReadPreparer = workoutReadPreparer,
                clock = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneId.of("UTC")),
            )
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `applyPendingChanges replaces a changed heart rate source in place without a pre-delete`() =
        runTest {
            seedTokens()
            val recordId = "hr-record"
            val oldTimestampMs = 1000L
            val sampleTime = Instant.parse("2026-06-20T09:00:00Z")
            val record =
                mockk<HeartRateRecord>(relaxed = true) {
                    every { metadata.id } returns recordId
                    every { metadata.device } returns null
                    every { metadata.dataOrigin.packageName } returns "pkg"
                    every { startTime } returns sampleTime
                    every { endTime } returns sampleTime
                    every { samples } returns
                        listOf(
                            mockk {
                                every { time } returns sampleTime
                                every { beatsPerMinute } returns 63L
                            },
                        )
                }
            val change =
                mockk<UpsertionChange>(relaxed = true) {
                    every { this@mockk.record } returns record
                }
            routeOneChange(dataType = HealthDataType.HEART_RATE, change = change)
            coEvery {
                changeIngestionStore.affectedDatesForRecords(HealthDataType.HEART_RATE, listOf(recordId), any())
            } returns setOf(epochDay(oldTimestampMs))

            val outcome = synchronizer.applyPendingChanges()

            assertEquals(
                setOf(epochDay(oldTimestampMs), sampleTime.atZone(ZoneId.systemDefault()).toLocalDate()),
                outcome.affectedDates,
            )
            // A pre-delete would journal a second ticket and defeat the writer's identical-payload
            // short-circuit; the source-scoped replace already removes rows the new payload drops.
            coVerify(exactly = 0) { changeIngestionStore.deleteRecords(HealthDataType.HEART_RATE, any()) }
            coVerifyOrder {
                changeIngestionStore.affectedDatesForRecords(HealthDataType.HEART_RATE, listOf(recordId), any())
                healthIngestionStore.replaceHeartRateSources(
                    match {
                        it.size == 1 && it[0].rows.size == 1 &&
                            it[0].rows[0].timestampMs == sampleTime.toEpochMilli()
                    },
                )
            }
        }

    @Test
    fun `applyPendingChanges replaces a changed hrv source in place without a pre-delete`() =
        runTest {
            seedTokens()
            val record =
                mockk<HeartRateVariabilityRmssdRecord>(relaxed = true) {
                    every { metadata.id } returns "hrv-record"
                    every { metadata.device } returns null
                    every { metadata.dataOrigin.packageName } returns "pkg"
                    every { time } returns Instant.parse("2026-06-20T03:00:00Z")
                    every { heartRateVariabilityMillis } returns 42.0
                }
            val change =
                mockk<UpsertionChange>(relaxed = true) {
                    every { this@mockk.record } returns record
                }
            routeOneChange(dataType = HealthDataType.HRV, change = change)

            synchronizer.applyPendingChanges()

            coVerify(exactly = 0) { changeIngestionStore.deleteRecords(HealthDataType.HRV, any()) }
            coVerify(exactly = 1) { healthIngestionStore.replaceHrvSources(match { it.size == 1 }) }
        }

    @Test
    fun `a heart rate upsertion from a de-selected device is deleted and not re-inserted`() =
        runTest {
            seedTokens()
            every { settingsRepo.userPreferences } returns
                flowOf(UserPreferences(deviceByDataType = mapOf(HealthDataType.HEART_RATE.name to "Selected Watch")))
            val recordId = "hr-other-device"
            val sampleTime = Instant.parse("2026-06-20T09:00:00Z")
            val record =
                mockk<HeartRateRecord>(relaxed = true) {
                    every { metadata.id } returns recordId
                    every { metadata.device } returns null
                    every { metadata.dataOrigin.packageName } returns "pkg"
                    every { startTime } returns sampleTime
                    every { endTime } returns sampleTime
                    every { samples } returns emptyList()
                }
            val change =
                mockk<UpsertionChange>(relaxed = true) {
                    every { this@mockk.record } returns record
                }
            routeOneChange(dataType = HealthDataType.HEART_RATE, change = change)

            synchronizer.applyPendingChanges()

            coVerify(exactly = 1) { changeIngestionStore.deleteRecords(HealthDataType.HEART_RATE, listOf(recordId)) }
            coVerify(exactly = 0) { healthIngestionStore.replaceHeartRateSources(any()) }
        }

    private fun seedTokens() {
        coEvery { tokenStore.get(HealthDataType.SLEEP) } returns "sleep-token"
        coEvery { tokenStore.get(HealthDataType.HEART_RATE) } returns "heart-token"
        coEvery { tokenStore.get(HealthDataType.HRV) } returns "hrv-token"
        coEvery { tokenStore.get(HealthDataType.EXERCISE) } returns "exercise-token"
        coEvery { tokenStore.get(HealthDataType.WEIGHT) } returns "weight-token"
        coEvery { tokenStore.get(HealthDataType.BODY_FAT) } returns "bodyfat-token"
        coEvery { tokenStore.get(HealthDataType.BLOOD_PRESSURE) } returns "bp-token"
        coEvery { tokenStore.get(HealthDataType.OXYGEN_SATURATION) } returns "spo2-token"
        coEvery { tokenStore.get(HealthDataType.BODY_TEMPERATURE) } returns "bodytemp-token"
        coEvery { tokenStore.get(HealthDataType.STEPS) } returns "steps-token"
        coEvery { tokenStore.get(HealthDataType.VO2_MAX) } returns "vo2max-token"
    }

    private fun routeOneChange(
        dataType: HealthDataType,
        change: androidx.health.connect.client.changes.Change,
    ) {
        HealthDataType.entries.forEach { current ->
            val token = tokenFor(current)
            val changes =
                if (current == dataType) {
                    listOf(change)
                } else {
                    emptyList()
                }
            coEvery { client.getChanges(token) } returns changesResponse(changes)
        }
    }

    private fun tokenFor(dataType: HealthDataType): String =
        when (dataType) {
            HealthDataType.SLEEP -> "sleep-token"
            HealthDataType.HEART_RATE -> "heart-token"
            HealthDataType.HRV -> "hrv-token"
            HealthDataType.EXERCISE -> "exercise-token"
            HealthDataType.WEIGHT -> "weight-token"
            HealthDataType.BODY_FAT -> "bodyfat-token"
            HealthDataType.BLOOD_PRESSURE -> "bp-token"
            HealthDataType.OXYGEN_SATURATION -> "spo2-token"
            HealthDataType.BODY_TEMPERATURE -> "bodytemp-token"
            HealthDataType.STEPS -> "steps-token"
            HealthDataType.VO2_MAX -> "vo2max-token"
        }

    private fun changesResponse(changes: List<androidx.health.connect.client.changes.Change>) =
        mockk<ChangesResponse>(relaxed = true) {
            every { changesTokenExpired } returns false
            every { this@mockk.changes } returns changes
            every { nextChangesToken } returns "next-token"
            every { hasMore } returns false
        }

    private fun epochDay(timestampMs: Long): LocalDate =
        Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()).toLocalDate()
}
