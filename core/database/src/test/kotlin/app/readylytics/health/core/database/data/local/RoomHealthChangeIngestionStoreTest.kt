package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.databaseschema.data.local.dao.ScanTypeStateDao
import app.readylytics.health.core.databaseschema.data.local.entity.ScanSeenIdEntity
import app.readylytics.health.core.databaseschema.data.local.entity.ScanTypeStateEntity
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.sync.CompleteTypeScan
import app.readylytics.health.core.model.domain.sync.HealthIngestionBatch
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.HrvInput
import app.readylytics.health.core.model.domain.sync.ScanIdentity
import app.readylytics.health.core.model.domain.sync.SleepSessionInput
import app.readylytics.health.core.model.domain.sync.SourceMetadata
import app.readylytics.health.core.model.domain.sync.SourcePayload
import app.readylytics.health.core.model.domain.sync.StepRecordInput
import app.readylytics.health.core.model.domain.sync.Vo2MaxInput
import app.readylytics.health.core.model.domain.sync.WeightInput
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.sync.IntervalKind
import app.readylytics.health.core.model.domain.sync.IntervalSourceRecord
import app.readylytics.health.core.model.domain.sync.PreparedWorkout
import app.readylytics.health.core.model.domain.sync.WorkoutInput
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class RoomHealthChangeIngestionStoreTest {
    private lateinit var database: HealthDatabase
    private lateinit var seedStore: RoomHealthIngestionStore
    private lateinit var changeStore: RoomHealthChangeIngestionStore

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        val daos =
            HealthRecordDaos(
                sleepSessionDao = database.sleepSessionDao(),
                sleepStageDao = database.sleepStageDao(),
                heartRateDao = database.heartRateDao(),
                hrvDao = database.hrvDao(),
                workoutDao = database.workoutDao(),
                workoutRoutePointDao = database.workoutRoutePointDao(),
                weightRecordDao = database.weightRecordDao(),
                bodyFatRecordDao = database.bodyFatRecordDao(),
                bloodPressureRecordDao = database.bloodPressureRecordDao(),
                oxygenSaturationRecordDao = database.oxygenSaturationRecordDao(),
                bodyTemperatureRecordDao = database.bodyTemperatureRecordDao(),
                stepRecordDao = database.stepRecordDao(),
                sourceRecordDao = database.sourceRecordDao(),
                minuteBucketMaintenanceDao = database.minuteBucketMaintenanceDao(),
            )
        seedStore =
            RoomHealthIngestionStore(
                daos = daos,
                dailySummaryDao = database.dailySummaryDao(),
                transactionRunner = RoomTransactionRunner(database),
                vo2MaxRecordDao = database.vo2MaxRecordDao(),
                scanTypeStateDao = database.scanTypeStateDao(),
            )
        changeStore = RoomHealthChangeIngestionStore(daos = daos, vo2MaxRecordDao = database.vo2MaxRecordDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `affectedDatesForRecord returns the sleep session's date range`() = runTest {
        val dayStartMs = START_MS
        val session = SleepSessionInput(
            id = "hc-sleep-1", startTime = dayStartMs, endTime = dayStartMs + 1 * 3_600_000L,
            durationMinutes = 60, efficiency = 0.9f, deepSleepMinutes = 40, remSleepMinutes = 15,
            lightSleepMinutes = 5, awakeMinutes = 0, sleepScore = null,
            startZoneOffsetSeconds = null, endZoneOffsetSeconds = null, deviceName = null,
        )
        seedStore.persist(HealthIngestionBatch(
            sleepSessions = listOf(session), sleepStages = emptyList(), heartRateSamples = emptyList(),
            hrvSamples = emptyList(), workouts = emptyList(), weights = emptyList(), bodyFatSamples = emptyList(),
            bloodPressureSamples = emptyList(), oxygenSaturationSamples = emptyList(),
            bodyTemperatureSamples = emptyList(), stepRecords = emptyList(),
        ))

        val dates = changeStore.affectedDatesForRecord(HealthDataType.SLEEP, "hc-sleep-1", ZoneId.of("UTC"))

        assertEquals(1, dates.size)
    }

    @Test
    fun `deleteRecord removes the heart rate record and its source ref`() = runTest {
        seedStore.replaceHeartRateSources(listOf(
            SourcePayload(
                SourceMetadata("hc-hr-1", 1000L, 1000L),
                listOf(
                    HeartRateInput(id = "hc-hr-1_1000", sourceId = "hc-hr-1", timestampMs = 1000L, beatsPerMinute = 60,
                        recordType = "RESTING", sessionId = null, deviceName = null),
                ),
            ),
        ))
        assertEquals(1, seedStore.countHeartRateInRange(0, 2000))

        changeStore.deleteRecord(HealthDataType.HEART_RATE, "hc-hr-1")

        assertEquals(0, seedStore.countHeartRateInRange(0, 2000))
    }

    @Test
    fun `sessionSpansOverlapping returns sleep and workout spans overlapping the window`() = runTest {
        val sleep = SleepSessionInput(
            id = "s1", startTime = 1_000L, endTime = 5_000L, durationMinutes = 1,
            efficiency = 1f, deepSleepMinutes = 0, remSleepMinutes = 0, lightSleepMinutes = 1,
            awakeMinutes = 0, sleepScore = null, startZoneOffsetSeconds = null,
            endZoneOffsetSeconds = null, deviceName = null,
        )
        val workout = WorkoutInput(
            id = "w1", startTime = 10_000L, endTime = 20_000L, exerciseType = "running",
            durationMinutes = 1, zone1Minutes = 0f, zone2Minutes = 0f, zone3Minutes = 0f,
            zone4Minutes = 0f, zone5Minutes = 0f, trimp = 0f, avgHr = 0f, deviceName = null,
        )
        seedStore.persist(HealthIngestionBatch(
            sleepSessions = listOf(sleep), sleepStages = emptyList(), heartRateSamples = emptyList(),
            hrvSamples = emptyList(), workouts = listOf(workout), weights = emptyList(),
            bodyFatSamples = emptyList(), bloodPressureSamples = emptyList(),
            oxygenSaturationSamples = emptyList(), bodyTemperatureSamples = emptyList(), stepRecords = emptyList(),
        ))

        val spans = changeStore.sessionSpansOverlapping(0L, 25_000L)

        assertEquals(listOf("s1"), spans.sleepSessions.map { it.id })
        assertEquals(listOf("w1"), spans.workouts.map { it.id })
    }

    @Test
    fun `heartRateSamplesForMetrics filters by record type and range`() = runTest {
        seedStore.replaceHeartRateSources(listOf(
            SourcePayload(
                SourceMetadata("hc-hr-2", 1000L, 1000L),
                listOf(
                    HeartRateInput(id = "hc-hr-2_1000", sourceId = "hc-hr-2", timestampMs = 1000L, beatsPerMinute = 140,
                        recordType = "EXERCISE", sessionId = "w1", deviceName = null),
                ),
            ),
            SourcePayload(
                SourceMetadata("hc-hr-3", 2000L, 2000L),
                listOf(
                    HeartRateInput(id = "hc-hr-3_2000", sourceId = "hc-hr-3", timestampMs = 2000L, beatsPerMinute = 60,
                        recordType = "RESTING", sessionId = null, deviceName = null),
                ),
            ),
        ))

        val samples = changeStore.heartRateSamplesForMetrics("EXERCISE", 0L, 5000L)

        assertEquals(1, samples.size)
        assertEquals(140, samples.single().beatsPerMinute)
    }

    @Test
    fun `affectedDatesForRecord returns dates for every heart rate sample sharing the source record id`() =
        runTest {
            seedStore.replaceHeartRateSources(
                listOf(
                    SourcePayload(
                        SourceMetadata("hc-hr-4", 1_000L, 90_000_000L),
                        listOf(
                            HeartRateInput(
                                id = "hc-hr-4_1000",
                                sourceId = "hc-hr-4",
                                timestampMs = 1_000L,
                                beatsPerMinute = 60,
                                recordType = "RESTING",
                                sessionId = null,
                                deviceName = null,
                            ),
                            HeartRateInput(
                                id = "hc-hr-4_90000000",
                                sourceId = "hc-hr-4",
                                timestampMs = 90_000_000L,
                                beatsPerMinute = 61,
                                recordType = "RESTING",
                                sessionId = null,
                                deviceName = null,
                            ),
                        ),
                    ),
                ),
            )

            val dates = changeStore.affectedDatesForRecord(HealthDataType.HEART_RATE, "hc-hr-4", ZoneId.of("UTC"))

            assertEquals(2, dates.size)
        }

    @Test
    fun `affectedDatesForRecord returns dates for every hrv sample sharing the source record id`() =
        runTest {
            seedStore.replaceHrvSources(
                listOf(
                    SourcePayload(
                        SourceMetadata("hc-hrv-1", 1_000L, 90_000_000L),
                        listOf(
                            HrvInput(
                                id = "hc-hrv-1_1000",
                                sourceId = "hc-hrv-1",
                                timestampMs = 1_000L,
                                rmssdMs = 40f,
                                recordType = "RESTING",
                                sessionId = null,
                                deviceName = null,
                            ),
                            HrvInput(
                                id = "hc-hrv-1_90000000",
                                sourceId = "hc-hrv-1",
                                timestampMs = 90_000_000L,
                                rmssdMs = 41f,
                                recordType = "RESTING",
                                sessionId = null,
                                deviceName = null,
                            ),
                        ),
                    ),
                ),
            )

            val dates = changeStore.affectedDatesForRecord(HealthDataType.HRV, "hc-hrv-1", ZoneId.of("UTC"))

            assertEquals(2, dates.size)
        }

    @Test
    fun `affectedDatesForRecord returns the workout's date range for EXERCISE`() =
        runTest {
            val workout = WorkoutInput(
                id = "hc-workout-1",
                startTime = Instant.parse("2026-03-10T23:00:00Z").toEpochMilli(),
                endTime = Instant.parse("2026-03-11T01:00:00Z").toEpochMilli(),
                exerciseType = "running", durationMinutes = 120,
                zone1Minutes = 0f, zone2Minutes = 0f, zone3Minutes = 0f, zone4Minutes = 0f, zone5Minutes = 0f,
                trimp = 0f, avgHr = 0f, deviceName = null,
            )
            seedStore.persist(batch(workouts = listOf(workout)))

            val dates = changeStore.affectedDatesForRecord(HealthDataType.EXERCISE, "hc-workout-1", ZoneId.of("UTC"))

            assertEquals(setOf(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 11)), dates)
        }

    @Test
    fun `affectedDatesForRecord returns the sample's date for a vitals type (WEIGHT)`() =
        runTest {
            val weightTime = Instant.parse("2026-05-01T12:00:00Z")
            seedStore.persist(
                batch(
                    weights = listOf(
                        WeightInput(
                            id = "hc-weight-1_${weightTime.toEpochMilli()}",
                            timestampMs = weightTime.toEpochMilli(),
                            weightKg = 70f, deviceName = null,
                        ),
                    ),
                ),
            )

            val dates = changeStore.affectedDatesForRecord(HealthDataType.WEIGHT, "hc-weight-1", ZoneId.of("UTC"))

            assertEquals(setOf(LocalDate.of(2026, 5, 1)), dates)
        }

    @Test
    fun `affectedDatesForRecord returns both dates for a steps record crossing midnight`() =
        runTest {
            // HC-005: a steps record spanning a day boundary must resolve both dates -- this is
            // the cross-midnight case the old synchronizer-level test used to cover before this
            // date-derivation logic moved into RoomHealthChangeIngestionStore.
            val recordId = "hc-steps-1"
            seedStore.persist(
                batch(
                    stepRecords = listOf(
                        StepRecordInput(
                            id = recordId,
                            startTime = Instant.parse("2026-03-10T22:00:00Z").toEpochMilli(),
                            endTime = Instant.parse("2026-03-11T00:00:00Z").toEpochMilli(),
                            count = 200L, deviceName = null,
                        ),
                    ),
                ),
            )

            val dates = changeStore.affectedDatesForRecord(HealthDataType.STEPS, recordId, ZoneId.of("UTC"))

            assertEquals(setOf(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 11)), dates)
        }

    @Test
    fun `affectedDatesForRecord returns the sample's date for VO2_MAX using its raw stable id`() =
        runTest {
            val vo2Time = Instant.parse("2026-05-01T12:00:00Z")
            seedStore.persist(
                batch(
                    vo2MaxSamples = listOf(
                        Vo2MaxInput(
                            id = "hc-vo2-1",
                            timestampMs = vo2Time.toEpochMilli(),
                            vo2Max = 45f,
                            measurementMethod = 1,
                            deviceName = "Watch A",
                        ),
                    ),
                ),
            )

            val dates = changeStore.affectedDatesForRecord(HealthDataType.VO2_MAX, "hc-vo2-1", ZoneId.of("UTC"))

            assertEquals(setOf(LocalDate.of(2026, 5, 1)), dates)
        }

    @Test
    fun `deleteRecord removes a VO2_MAX record seeded 45 days ago leaving no stale carry-forward`() =
        runTest {
            val today = LocalDate.of(2026, 9, 14)
            val seededAt = today.minusDays(45).atStartOfDay(ZoneId.of("UTC")).toInstant()
            seedStore.persist(
                batch(
                    vo2MaxSamples = listOf(
                        Vo2MaxInput(
                            id = "hc-vo2-old",
                            timestampMs = seededAt.toEpochMilli(),
                            vo2Max = 42f,
                            measurementMethod = null,
                            deviceName = "Watch A",
                        ),
                    ),
                ),
            )
            assertEquals(1, database.vo2MaxRecordDao().getByTimeRange(0, Long.MAX_VALUE).size)

            changeStore.deleteRecord(HealthDataType.VO2_MAX, "hc-vo2-old")

            // Matches a clean DB that never persisted the record -- no stale carry-forward.
            assertTrue(database.vo2MaxRecordDao().getByTimeRange(0, Long.MAX_VALUE).isEmpty())
            assertNull(database.vo2MaxRecordDao().getById("hc-vo2-old"))
        }

    @Test
    fun `reconcileWindow deletes VO2_MAX records absent from an empty complete scan`() =
        runTest {
            val vo2Time = Instant.parse("2026-05-01T12:00:00Z")
            seedStore.persist(
                batch(
                    vo2MaxSamples = listOf(
                        Vo2MaxInput(
                            id = "hc-vo2-empty",
                            timestampMs = vo2Time.toEpochMilli(),
                            vo2Max = 40f,
                            measurementMethod = null,
                            deviceName = null,
                        ),
                    ),
                ),
            )
            val dayStart = vo2Time.atZone(ZoneId.of("UTC")).toLocalDate().atStartOfDay(ZoneId.of("UTC")).toInstant()
            val scanId = ScanIdentity("change-test-1", "0")
            stage(scanId, HealthDataType.VO2_MAX, emptyList())
            val scan =
                CompleteTypeScan(
                    type = HealthDataType.VO2_MAX,
                    windowStartMs = dayStart.toEpochMilli(),
                    windowEndExclusiveMs = dayStart.plusSeconds(86_400).toEpochMilli(),
                    sourceSelectionId = "",
                    scan = scanId,
                )

            val affected = seedStore.reconcileWindow(scan, ZoneId.of("UTC"))

            assertTrue(database.vo2MaxRecordDao().getByTimeRange(0, Long.MAX_VALUE).isEmpty())
            assertEquals(LocalDate.of(2026, 5, 1), affected?.start)
        }

    @Test
    fun `reconcileWindow with a stable timestamp tie deletes only the id absent from the scan`() =
        runTest {
            val sharedTime = Instant.parse("2026-05-01T12:00:00Z")
            seedStore.persist(
                batch(
                    vo2MaxSamples = listOf(
                        Vo2MaxInput(
                            id = "hc-vo2-keep",
                            timestampMs = sharedTime.toEpochMilli(),
                            vo2Max = 41f,
                            measurementMethod = null,
                            deviceName = null,
                        ),
                        Vo2MaxInput(
                            id = "hc-vo2-gone",
                            timestampMs = sharedTime.toEpochMilli(),
                            vo2Max = 39f,
                            measurementMethod = null,
                            deviceName = null,
                        ),
                    ),
                ),
            )
            val dayStart = sharedTime.atZone(ZoneId.of("UTC")).toLocalDate().atStartOfDay(ZoneId.of("UTC")).toInstant()
            val scanId = ScanIdentity("change-test-2", "0")
            stage(scanId, HealthDataType.VO2_MAX, listOf("hc-vo2-keep"))
            val scan =
                CompleteTypeScan(
                    type = HealthDataType.VO2_MAX,
                    windowStartMs = dayStart.toEpochMilli(),
                    windowEndExclusiveMs = dayStart.plusSeconds(86_400).toEpochMilli(),
                    sourceSelectionId = "",
                    scan = scanId,
                )

            seedStore.reconcileWindow(scan, ZoneId.of("UTC"))

            val remaining = database.vo2MaxRecordDao().getByTimeRange(0, Long.MAX_VALUE)
            assertEquals(listOf("hc-vo2-keep"), remaining.map { it.id })
        }

    @Test
    fun `reconcileWindow for VO2_MAX leaves an unrelated WEIGHT record intact`() =
        runTest {
            val sampleTime = Instant.parse("2026-05-01T12:00:00Z")
            seedStore.persist(
                batch(
                    weights = listOf(
                        WeightInput(
                            id = "hc-weight-untouched_${sampleTime.toEpochMilli()}",
                            timestampMs = sampleTime.toEpochMilli(),
                            weightKg = 70f,
                            deviceName = null,
                        ),
                    ),
                    vo2MaxSamples = listOf(
                        Vo2MaxInput(
                            id = "hc-vo2-deleted",
                            timestampMs = sampleTime.toEpochMilli(),
                            vo2Max = 38f,
                            measurementMethod = null,
                            deviceName = null,
                        ),
                    ),
                ),
            )
            val dayStart = sampleTime.atZone(ZoneId.of("UTC")).toLocalDate().atStartOfDay(ZoneId.of("UTC")).toInstant()
            val scanId = ScanIdentity("change-test-3", "0")
            stage(scanId, HealthDataType.VO2_MAX, emptyList())
            val scan =
                CompleteTypeScan(
                    type = HealthDataType.VO2_MAX,
                    windowStartMs = dayStart.toEpochMilli(),
                    windowEndExclusiveMs = dayStart.plusSeconds(86_400).toEpochMilli(),
                    sourceSelectionId = "",
                    scan = scanId,
                )

            seedStore.reconcileWindow(scan, ZoneId.of("UTC"))

            assertTrue(database.vo2MaxRecordDao().getByTimeRange(0, Long.MAX_VALUE).isEmpty())
            assertEquals(1, database.weightRecordDao().getByTimeRange(0, Long.MAX_VALUE).size)
        }

        @Test
        fun `workoutsOverlapping returns stored workouts that overlap the range`() =
            runTest {
                val w1 =
                    WorkoutInput(
                        id = "w1",
                        startTime = 10_000L,
                        endTime = 30_000L,
                        exerciseType = "RUNNING",
                        durationMinutes = 20,
                        zone1Minutes = 0f,
                        zone2Minutes = 0f,
                        zone3Minutes = 0f,
                        zone4Minutes = 0f,
                        zone5Minutes = 0f,
                        trimp = 0f,
                        avgHr = 0f,
                        deviceName = null,
                    )
                val w2 =
                    WorkoutInput(
                        id = "w2",
                        startTime = 40_000L,
                        endTime = 60_000L,
                        exerciseType = "CYCLING",
                        durationMinutes = 20,
                        zone1Minutes = 0f,
                        zone2Minutes = 0f,
                        zone3Minutes = 0f,
                        zone4Minutes = 0f,
                        zone5Minutes = 0f,
                        trimp = 0f,
                        avgHr = 0f,
                        deviceName = null,
                    )
                seedStore.persist(batch(workouts = listOf(w1, w2)))

                // Query overlapping [15, 25) -> should find w1 only
                val overlapping1 = changeStore.workoutsOverlapping(15_000L, 25_000L)
                assertEquals(1, overlapping1.size)
                assertEquals("w1", overlapping1[0].id)

                // Query touching boundary [30, 40) -> neither overlaps half-open
                val overlappingBoundary = changeStore.workoutsOverlapping(30_000L, 40_000L)
                assertTrue(overlappingBoundary.isEmpty())

                // Query overlapping [25, 45) -> both w1 and w2 overlap
                val overlappingBoth = changeStore.workoutsOverlapping(25_000L, 45_000L)
                assertEquals(2, overlappingBoth.size)
            }

        @Test
        fun `persistIntervalEnrichment updates workouts and source metadata atomically`() =
            runTest {
                val w1 =
                    WorkoutInput(
                        id = "w1",
                        startTime = 10_000L,
                        endTime = 30_000L,
                        exerciseType = "RUNNING",
                        durationMinutes = 20,
                        zone1Minutes = 0f,
                        zone2Minutes = 0f,
                        zone3Minutes = 0f,
                        zone4Minutes = 0f,
                        zone5Minutes = 0f,
                        trimp = 0f,
                        avgHr = 0f,
                        deviceName = null,
                    )
                seedStore.persist(batch(workouts = listOf(w1)))

                val prepared =
                    PreparedWorkout(
                        workout = w1,
                        route = ReadOutcome.Denied,
                        distanceMeters = ReadOutcome.Available(1500f),
                        elevationMeters = ReadOutcome.Available(50f),
                    )
                val sourceRecord =
                    IntervalSourceRecord(
                        sourceId = "dist-1",
                        kind = IntervalKind.DISTANCE,
                        startMs = 15_000L,
                        endExclusiveMs = 25_000L,
                        originPackage = "com.strava",
                    )

                changeStore.persistIntervalEnrichment(
                    preparedWorkouts = listOf(prepared),
                    sourceUpserts = listOf(sourceRecord),
                    sourceDeletes = emptyList(),
                    dirtyDates = setOf(LocalDate.parse("2026-08-31")),
                )

                // Verify workout updated with distance and elevation
                val updatedWorkout = database.workoutDao().getById("w1")
                assertEquals(1500f, updatedWorkout?.totalDistanceMeters)
                assertEquals(50f, updatedWorkout?.elevationGainMeters)

                // Verify source metadata stored and retrievable via getIntervalSource
                val retrievedSource = changeStore.getIntervalSource("dist-1")
                assertEquals("dist-1", retrievedSource?.sourceId)
                assertEquals(IntervalKind.DISTANCE, retrievedSource?.kind)
                assertEquals(15_000L, retrievedSource?.startMs)
                assertEquals(25_000L, retrievedSource?.endExclusiveMs)

                // Now test source deletion
                changeStore.persistIntervalEnrichment(
                    preparedWorkouts = emptyList(),
                    sourceUpserts = emptyList(),
                    sourceDeletes = listOf("dist-1"),
                    dirtyDates = emptySet(),
                )
                assertNull(changeStore.getIntervalSource("dist-1"))
            }

    private fun batch(
        sleepSessions: List<SleepSessionInput> = emptyList(),
        workouts: List<WorkoutInput> = emptyList(),
        weights: List<WeightInput> = emptyList(),
        stepRecords: List<StepRecordInput> = emptyList(),
        vo2MaxSamples: List<Vo2MaxInput> = emptyList(),
    ) = HealthIngestionBatch(
        sleepSessions = sleepSessions, sleepStages = emptyList(), heartRateSamples = emptyList(),
        hrvSamples = emptyList(), workouts = workouts, weights = weights, bodyFatSamples = emptyList(),
        bloodPressureSamples = emptyList(), oxygenSaturationSamples = emptyList(),
        bodyTemperatureSamples = emptyList(), stepRecords = stepRecords, vo2MaxSamples = vo2MaxSamples,
    )

    private suspend fun stage(
        scanId: ScanIdentity,
        type: HealthDataType,
        ids: List<String>,
        complete: Boolean = true,
    ) {
        database.scanStagingDao().insertSeenIds(
            ids.map { ScanSeenIdEntity(scanId.runId, scanId.chunkId, type.name, it) },
        )
        database.scanTypeStateDao().upsertState(
            ScanTypeStateEntity(
                runId = scanId.runId,
                chunkId = scanId.chunkId,
                recordType = type.name,
                state = if (complete) ScanTypeStateDao.STATE_COMPLETE else ScanTypeStateDao.STATE_SCANNING,
                stagedCount = ids.size,
                updatedAtMs = 0L,
            ),
        )
    }

    private companion object {
        const val START_MS = 1_700_000_000_000L
    }
}
