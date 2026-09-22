package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.model.DomainBloodPressureRecord
import app.readylytics.health.core.model.domain.model.DomainBodyFatRecord
import app.readylytics.health.core.model.domain.model.DomainBodyTemperatureRecord
import app.readylytics.health.core.model.domain.model.DomainExerciseSessionRecord
import app.readylytics.health.core.model.domain.model.DomainHeartRateRecord
import app.readylytics.health.core.model.domain.model.DomainHeartRateSample
import app.readylytics.health.core.model.domain.model.DomainHrvRecord
import app.readylytics.health.core.model.domain.model.DomainOxygenSaturationRecord
import app.readylytics.health.core.model.domain.model.DomainSleepSessionRecord
import app.readylytics.health.core.model.domain.model.DomainStepsRecord
import app.readylytics.health.core.model.domain.model.DomainVo2MaxRecord
import app.readylytics.health.core.model.domain.model.DomainWeightRecord
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.model.WorkoutRoutePoint
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.PermissionStatus
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.sync.CompleteTypeScan
import app.readylytics.health.core.model.domain.sync.HealthIngestionBatch
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.HrvInput
import app.readylytics.health.core.model.domain.sync.ScanIdentity
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import app.readylytics.health.core.model.domain.sync.SourcePayload
import app.readylytics.health.core.model.domain.sync.TypeScanState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * PERF-001: bounds the in-memory transform step inside [HeartSampleStreamer] -- a dense Health
 * Connect page must be mapped and persisted in slices no larger than
 * [TRANSFORM_SAMPLE_BUDGET] nested samples (HR) / parent records (HRV), regardless of
 * how many parent records or nested samples the page itself contains.
 */
class TransformBufferBoundTest {
    private fun prefs(): UserPreferences = UserPreferences()

    private fun coordinator(
        repo: HealthConnectRepository,
        store: HealthIngestionStore,
        staging: app.readylytics.health.core.model.domain.sync.ScanStagingStore =
            InMemoryScanStagingStore(),
    ) = HealthIngestionCoordinator(repo, store, staging)

    private fun densePage(
        parents: Int,
        samplesEach: Int,
        idPrefix: String = "hr",
    ): List<DomainHeartRateRecord> =
        (0 until parents).map { p ->
            DomainHeartRateRecord(
                id = "$idPrefix-$p",
                deviceName = "Pixel Watch",
                samples =
                    (0 until samplesEach).map { s ->
                        DomainHeartRateSample(
                            time = Instant.ofEpochMilli((p.toLong() * samplesEach + s) * 1000L),
                            beatsPerMinute = 70,
                        )
                    },
            )
        }

    private fun hrvPage(
        records: Int,
        idPrefix: String = "hrv",
    ): List<DomainHrvRecord> =
        (0 until records).map { i ->
            DomainHrvRecord(
                id = "$idPrefix-$i",
                time = Instant.ofEpochMilli(i * 1000L),
                rmssdMs = 40f,
                deviceName = "Pixel Watch",
            )
        }

    @Test
    fun aDenseNestedPageIsMappedInBoundedSlices() =
        runTest {
            // One page, 40 parents x 500 nested samples = 20_000 samples, budget 5_000.
            val repo = FakeTransformRepository(hrPages = listOf(densePage(parents = 40, samplesEach = 500)))
            val store = RecordingTransformIngestionStore()
            val coord = coordinator(repo, store)

            coord.ingestWindow(
                windowStart = Instant.ofEpochMilli(0),
                windowEnd = Instant.ofEpochMilli(86_400_000),
                prefs = prefs(),
                scanIdentity = ScanIdentity("run-a", "0"),
            )

            assertEquals(20_000, store.hrPersistedRowCount)
            assertTrue(
                "largest mapped slice ${store.hrMaxSliceSampleCount} must stay within the budget",
                store.hrMaxSliceSampleCount <= TRANSFORM_SAMPLE_BUDGET,
            )
            assertTrue("expected more than one slice", store.hrSliceCount > 1)
        }

    @Test
    fun oneParentLargerThanTheBudgetIsStillPersistedWhole() =
        runTest {
            val repo = FakeTransformRepository(hrPages = listOf(densePage(parents = 1, samplesEach = 12_000)))
            val store = RecordingTransformIngestionStore()
            val coord = coordinator(repo, store)

            coord.ingestWindow(
                windowStart = Instant.ofEpochMilli(0),
                windowEnd = Instant.ofEpochMilli(86_400_000),
                prefs = prefs(),
                scanIdentity = ScanIdentity("run-a", "0"),
            )

            assertEquals(12_000, store.hrPersistedRowCount)
            assertEquals(1, store.hrSliceCount)
        }

    @Test
    fun aDenseHrvPageIsSlicedByParentCountSinceEachRecordHoldsOneValue() =
        runTest {
            // HRV: one value per record, so parent count == sample count. 12_000 records, budget 5_000
            // -> ceil(12_000 / 5_000) = 3 slices.
            val repo = FakeTransformRepository(hrvPages = listOf(hrvPage(records = 12_000)))
            val store = RecordingTransformIngestionStore()
            val coord = coordinator(repo, store)

            coord.ingestWindow(
                windowStart = Instant.ofEpochMilli(0),
                windowEnd = Instant.ofEpochMilli(86_400_000),
                prefs = prefs(),
                scanIdentity = ScanIdentity("run-a", "0"),
            )

            assertEquals(12_000, store.hrvPersistedRowCount)
            assertEquals(3, store.hrvSliceCount)
            assertTrue(store.hrvMaxSliceSampleCount <= TRANSFORM_SAMPLE_BUDGET)
        }

    @Test
    fun everyRecordAcrossSlicedPagesIsStillStagedAndTheTypeScanCompletes() =
        runTest {
            // Two dense pages, each internally sliced into multiple transform batches. Per-page
            // staging (Task 4) must still cover every id from both pages and only mark the type
            // COMPLETE once the whole paged read (not just the first slice of the last page) is done.
            val pageA = densePage(parents = 40, samplesEach = 500, idPrefix = "a")
            val pageB = densePage(parents = 40, samplesEach = 500, idPrefix = "b")
            val repo = FakeTransformRepository(hrPages = listOf(pageA, pageB))
            val store = RecordingTransformIngestionStore()
            val staging = InMemoryScanStagingStore()
            val coord = coordinator(repo, store, staging)
            val scanId = ScanIdentity("run-a", "0")

            coord.ingestWindow(
                windowStart = Instant.ofEpochMilli(0),
                windowEnd = Instant.ofEpochMilli(86_400_000),
                prefs = prefs(),
                scanIdentity = scanId,
            )

            val expectedIds = (pageA + pageB).map { it.id }.toSet()
            assertEquals(expectedIds, staging.stagedIds(scanId, HealthDataType.HEART_RATE))
            assertEquals(TypeScanState.COMPLETE, staging.stateOf(scanId, HealthDataType.HEART_RATE))
            assertEquals(40_000, store.hrPersistedRowCount)
            assertTrue(store.hrSliceCount > 2)
        }

    private class FakeTransformRepository(
        val hrPages: List<List<DomainHeartRateRecord>> = emptyList(),
        val hrvPages: List<List<DomainHrvRecord>> = emptyList(),
    ) : HealthConnectRepository {
        override val criticalPermissions: Set<String> = emptySet()
        override val requiredPermissions: Set<String> = emptySet()
        override val optionalPermissions: Set<String> = emptySet()
        override val allPermissions: Set<String> = emptySet()
        override val backgroundReadPermission: String = ""

        override fun isAvailable(): Boolean = true

        override suspend fun checkPermissions(): PermissionStatus = PermissionStatus.Granted

        override suspend fun hasBodyTemperaturePermission(): Boolean = true

        override suspend fun hasStepsPermission(): Boolean = true

        override suspend fun hasWeightPermission(): Boolean = true

        override suspend fun hasDistancePermission(): Boolean = true

        override suspend fun hasBodyFatPermission(): Boolean = true

        override suspend fun hasBloodPressurePermission(): Boolean = true

        override suspend fun hasOxygenSaturationPermission(): Boolean = true

        override suspend fun hasExerciseRoutesPermission(): Boolean = true

        override suspend fun hasVo2MaxPermission(): Boolean = false

        override suspend fun readSleepSessions(
            from: Instant,
            to: Instant,
        ): ReadOutcome<List<DomainSleepSessionRecord>> = ReadOutcome.Available(emptyList())

        override suspend fun readHeartRateSamples(
            from: Instant,
            to: Instant,
        ): ReadOutcome<List<DomainHeartRateRecord>> = ReadOutcome.Available(emptyList())

        override suspend fun readHrvSamples(
            from: Instant,
            to: Instant,
        ): ReadOutcome<List<DomainHrvRecord>> = ReadOutcome.Available(emptyList())

        override suspend fun readHeartRateSamplesPaged(
            from: Instant,
            to: Instant,
            startPageToken: String?,
            onPage: suspend (records: List<DomainHeartRateRecord>, nextPageToken: String?) -> Unit,
        ): ReadOutcome<Unit> {
            for ((index, page) in hrPages.withIndex()) {
                val nextToken = if (index + 1 < hrPages.size) "hr-token-${index + 1}" else null
                onPage(page, nextToken)
            }
            return ReadOutcome.Available(Unit)
        }

        override suspend fun readHrvSamplesPaged(
            from: Instant,
            to: Instant,
            startPageToken: String?,
            onPage: suspend (records: List<DomainHrvRecord>, nextPageToken: String?) -> Unit,
        ): ReadOutcome<Unit> {
            for ((index, page) in hrvPages.withIndex()) {
                val nextToken = if (index + 1 < hrvPages.size) "hrv-token-${index + 1}" else null
                onPage(page, nextToken)
            }
            return ReadOutcome.Available(Unit)
        }

        override suspend fun readExerciseSessions(
            from: Instant,
            to: Instant,
            includeDetails: Boolean,
        ): ReadOutcome<List<DomainExerciseSessionRecord>> = ReadOutcome.Available(emptyList())

        override suspend fun readStepsRecords(
            from: Instant,
            to: Instant,
        ): ReadOutcome<List<DomainStepsRecord>> = ReadOutcome.Available(emptyList())

        override suspend fun readSteps(
            from: Instant,
            to: Instant,
        ): ReadOutcome<Long> = ReadOutcome.Available(0L)

        override suspend fun readDailyStepTotals(
            from: Instant,
            to: Instant,
            zoneId: ZoneId,
        ): ReadOutcome<Map<LocalDate, Long>> = ReadOutcome.Available(emptyMap())

        override suspend fun discoverDevices(windowDays: Int): List<String> = emptyList()

        override suspend fun readWeightRecords(
            from: Instant,
            to: Instant,
        ): ReadOutcome<List<DomainWeightRecord>> = ReadOutcome.Available(emptyList())

        override suspend fun readBodyFatRecords(
            from: Instant,
            to: Instant,
        ): ReadOutcome<List<DomainBodyFatRecord>> = ReadOutcome.Available(emptyList())

        override suspend fun readBloodPressureRecords(
            from: Instant,
            to: Instant,
        ): ReadOutcome<List<DomainBloodPressureRecord>> = ReadOutcome.Available(emptyList())

        override suspend fun readOxygenSaturationRecords(
            from: Instant,
            to: Instant,
        ): ReadOutcome<List<DomainOxygenSaturationRecord>> = ReadOutcome.Available(emptyList())

        override suspend fun readBodyTemperatureRecords(
            from: Instant,
            to: Instant,
        ): ReadOutcome<List<DomainBodyTemperatureRecord>> = ReadOutcome.Available(emptyList())

        override suspend fun readVo2MaxRecords(
            startTime: Instant,
            endTime: Instant,
        ): ReadOutcome<List<DomainVo2MaxRecord>> = ReadOutcome.Available(emptyList())

        override suspend fun readExerciseSession(id: String): DomainExerciseSessionRecord? = null
    }

    private class RecordingTransformIngestionStore : HealthIngestionStore {
        val persistedHeartRateSources = mutableListOf<List<SourcePayload<HeartRateInput>>>()
        val persistedHrvSources = mutableListOf<List<SourcePayload<HrvInput>>>()

        val hrSliceCount: Int get() = persistedHeartRateSources.size
        val hrPersistedRowCount: Int get() = persistedHeartRateSources.sumOf { it.totalRows() }
        val hrMaxSliceSampleCount: Int get() = persistedHeartRateSources.maxOfOrNull { it.totalRows() } ?: 0

        val hrvSliceCount: Int get() = persistedHrvSources.size
        val hrvPersistedRowCount: Int get() = persistedHrvSources.sumOf { it.totalRows() }
        val hrvMaxSliceSampleCount: Int get() = persistedHrvSources.maxOfOrNull { it.totalRows() } ?: 0

        private fun List<SourcePayload<*>>.totalRows(): Int = sumOf { it.rows.size }

        override suspend fun persist(batch: HealthIngestionBatch) = Unit

        override suspend fun replaceHeartRateSources(sources: List<SourcePayload<HeartRateInput>>) {
            persistedHeartRateSources += sources
        }

        override suspend fun replaceHrvSources(sources: List<SourcePayload<HrvInput>>) {
            persistedHrvSources += sources
        }

        override suspend fun clearFrozenBaselines(
            start: LocalDate,
            endExclusive: LocalDate,
            zoneId: ZoneId,
        ) = Unit

        override suspend fun countHeartRateInRange(
            startMs: Long,
            endMs: Long,
        ): Int = 0

        override suspend fun countHrvInRange(
            startMs: Long,
            endMs: Long,
        ): Int = 0

        override suspend fun countSleepSessionsInRange(
            startMs: Long,
            endMs: Long,
        ): Int = 0

        override suspend fun countWorkoutsInRange(
            startMs: Long,
            endMs: Long,
        ): Int = 0

        override suspend fun persistSingleWorkoutRoute(
            workoutId: String,
            routePoints: List<WorkoutRoutePoint>,
            routeState: String,
            totalDistanceMeters: Float?,
            avgSpeedKmh: Float?,
            elevationGainMeters: Float?,
        ) = Unit

        override suspend fun reconcileWindow(
            scan: CompleteTypeScan,
            zoneId: ZoneId,
        ): ScoreInvalidation.AffectedRange? = null
    }
}
