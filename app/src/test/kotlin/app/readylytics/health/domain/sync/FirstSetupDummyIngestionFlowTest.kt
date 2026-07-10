package app.readylytics.health.domain.sync

import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.model.DomainBloodPressureRecord
import app.readylytics.health.domain.model.DomainBodyFatRecord
import app.readylytics.health.domain.model.DomainExerciseSessionRecord
import app.readylytics.health.domain.model.DomainHeartRateRecord
import app.readylytics.health.domain.model.DomainHeartRateSample
import app.readylytics.health.domain.model.DomainHrvRecord
import app.readylytics.health.domain.model.DomainOxygenSaturationRecord
import app.readylytics.health.domain.model.DomainSleepSessionRecord
import app.readylytics.health.domain.model.DomainSleepStage
import app.readylytics.health.domain.model.DomainSleepStageType
import app.readylytics.health.domain.model.DomainStepsRecord
import app.readylytics.health.domain.model.DomainWeightRecord
import app.readylytics.health.domain.model.HealthDataType
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.repository.PermissionStatus
import app.readylytics.health.domain.repository.ScoringRepository
import app.readylytics.health.domain.scoring.RasSourceModeBootstrapUseCase
import app.readylytics.health.domain.sync.link.SessionLinkReconciler
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class FirstSetupDummyIngestionFlowTest {
    @Test
    fun `daily sync persists deterministic dummy sleep workout hr and hrv batch`() =
        runTest {
            val hcRepo = FakeFirstSetupHealthConnectRepository()
            val ingestionStore = RecordingHealthIngestionStore()

            buildUseCase(hcRepo, ingestionStore).run(windowDays = 1, onProgress = null)

            val batch = ingestionStore.persisted.single()
            val expectedBatch =
                HealthIngestionBatch(
                    sleepSessions =
                        listOf(
                            SleepSessionInput(
                                id = "sleep-1",
                                startTime = 1782684000000,
                                endTime = 1782712800000,
                                durationMinutes = 480,
                                efficiency = 100f,
                                deepSleepMinutes = 120,
                                remSleepMinutes = 180,
                                lightSleepMinutes = 180,
                                awakeMinutes = 0,
                                sleepScore = null,
                                startZoneOffsetSeconds = 0,
                                endZoneOffsetSeconds = 0,
                                deviceName = "Pixel Watch",
                            ),
                        ),
                    sleepStages =
                        listOf(
                            SleepStageInput(
                                sessionId = "sleep-1",
                                stageType = "LIGHT",
                                startTime = 1782684000000,
                                endTime = 1782694800000,
                                durationMinutes = 180,
                            ),
                            SleepStageInput(
                                sessionId = "sleep-1",
                                stageType = "DEEP",
                                startTime = 1782694800000,
                                endTime = 1782702000000,
                                durationMinutes = 120,
                            ),
                            SleepStageInput(
                                sessionId = "sleep-1",
                                stageType = "REM",
                                startTime = 1782702000000,
                                endTime = 1782712800000,
                                durationMinutes = 180,
                            ),
                        ),
                    heartRateSamples =
                        listOf(
                            HeartRateInput(
                                id = "hr-1_1782687600000",
                                timestampMs = 1782687600000,
                                beatsPerMinute = 52,
                                recordType = "SLEEP",
                                sessionId = "sleep-1",
                                deviceName = "Pixel Watch",
                            ),
                            HeartRateInput(
                                id = "hr-2_1782724500000",
                                timestampMs = 1782724500000,
                                beatsPerMinute = 148,
                                recordType = "EXERCISE",
                                sessionId = "workout-1",
                                deviceName = "Pixel Watch",
                            ),
                            HeartRateInput(
                                id = "hr-3_1782741600000",
                                timestampMs = 1782741600000,
                                beatsPerMinute = 64,
                                recordType = "RESTING",
                                sessionId = null,
                                deviceName = "Pixel Watch",
                            ),
                        ),
                    hrvSamples =
                        listOf(
                            HrvInput(
                                id = "hrv-1_1782691200000",
                                timestampMs = 1782691200000,
                                rmssdMs = 38.5f,
                                recordType = "SLEEP",
                                sessionId = "sleep-1",
                                deviceName = "Pixel Watch",
                            ),
                        ),
                    workouts =
                        listOf(
                            WorkoutInput(
                                id = "workout-1",
                                startTime = 1782723600000,
                                endTime = 1782727200000,
                                exerciseType = "running",
                                durationMinutes = 60,
                                zone1Minutes = 0f,
                                zone2Minutes = 0f,
                                zone3Minutes = 45f,
                                zone4Minutes = 0f,
                                zone5Minutes = 0f,
                                trimp = 135f,
                                avgHr = 148f,
                                deviceName = "Pixel Watch",
                            ),
                        ),
                    weights = emptyList(),
                    bodyFatSamples = emptyList(),
                    bloodPressureSamples = emptyList(),
                    oxygenSaturationSamples = listOf(),
                )
            assertEquals(expectedBatch, batch)
        }

    @Test
    fun `daily sync repeated runs keep stable ids for dummy dataset`() =
        runTest {
            val hcRepo = FakeFirstSetupHealthConnectRepository()
            val ingestionStore = RecordingHealthIngestionStore()
            val useCase = buildUseCase(hcRepo, ingestionStore)

            useCase.run(windowDays = 1, onProgress = null)
            useCase.run(windowDays = 1, onProgress = null)

            assertEquals(2, ingestionStore.persisted.size)
            val first = ingestionStore.persisted.first()
            val second = ingestionStore.persisted.last()

            assertEquals(first, second)
        }

    private fun buildUseCase(
        hcRepo: HealthConnectRepository,
        ingestionStore: HealthIngestionStore,
    ): DailySyncUseCase {
        val settingsRepo = mockk<SettingsRepository>(relaxed = true)
        val scoringRepository = mockk<ScoringRepository>(relaxed = true)
        val sessionLinkReconciler = mockk<SessionLinkReconciler>(relaxed = true)
        val rasBootstrap = mockk<RasSourceModeBootstrapUseCase>(relaxed = true)
        val changeSynchronizer = mockk<HealthChangeSynchronizer>(relaxed = true)

        every { settingsRepo.userPreferences } returns flowOf(UserPreferences())
        coEvery { changeSynchronizer.applyPendingChanges() } returns
            HealthChangeSyncOutcome(
                affectedDates = emptySet(),
                requiresFullResync = false,
                nextTokens = mapOf(HealthDataType.SLEEP to "next-sleep-token"),
            )
        coJustRun { changeSynchronizer.commitTokens(any()) }

        return DailySyncUseCase(
            settingsRepo = settingsRepo,
            sessionLinkReconciler = sessionLinkReconciler,
            rasSourceModeBootstrapUseCase = rasBootstrap,
            changeSynchronizer = changeSynchronizer,
            healthIngestionStore = ingestionStore,
            ingestionCoordinator = HealthIngestionCoordinator(hcRepo, ingestionStore),
            stepCountFetcher = StepCountFetcher(hcRepo),
            recomputeSupport = DailyRecomputeSupport(scoringRepository, settingsRepo),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    private class RecordingHealthIngestionStore : HealthIngestionStore {
        val persisted = mutableListOf<HealthIngestionBatch>()
        val clearedRanges = mutableListOf<Pair<LocalDate, LocalDate>>()

        override suspend fun persist(batch: HealthIngestionBatch) {
            persisted += batch
        }

        override suspend fun clearFrozenBaselines(
            start: LocalDate,
            endExclusive: LocalDate,
        ) {
            clearedRanges += start to endExclusive
        }

        override suspend fun countHeartRateInRange(startMs: Long, endMs: Long): Int = 0
        override suspend fun countHrvInRange(startMs: Long, endMs: Long): Int = 0
        override suspend fun countSleepSessionsInRange(startMs: Long, endMs: Long): Int = 0
        override suspend fun countWorkoutsInRange(startMs: Long, endMs: Long): Int = 0
    }

    private class FakeFirstSetupHealthConnectRepository : HealthConnectRepository {
        override val criticalPermissions: Set<String> = emptySet()
        override val requiredPermissions: Set<String> = emptySet()
        override val optionalPermissions: Set<String> = emptySet()
        override val allPermissions: Set<String> = emptySet()
        override val backgroundReadPermission: String = ""

        override fun isAvailable(): Boolean = true

        override suspend fun checkPermissions(): PermissionStatus = PermissionStatus.Granted

        override suspend fun readSleepSessions(
            from: Instant,
            to: Instant,
        ): List<DomainSleepSessionRecord> = listOf(sleepSession)

        override suspend fun readHeartRateSamples(
            from: Instant,
            to: Instant,
        ): List<DomainHeartRateRecord> = heartRateRecords

        override suspend fun readHrvSamples(
            from: Instant,
            to: Instant,
        ): List<DomainHrvRecord> = listOf(hrvRecord)

        override suspend fun readExerciseSessions(
            from: Instant,
            to: Instant,
        ): List<DomainExerciseSessionRecord> = listOf(workoutSession)

        override suspend fun readStepsRecords(
            from: Instant,
            to: Instant,
        ): List<DomainStepsRecord> = emptyList()

        override suspend fun readSteps(
            from: Instant,
            to: Instant,
        ): Long = 4200L

        override suspend fun readStepsRange(
            from: Instant,
            to: Instant,
        ): Map<LocalDate, Long> = emptyMap()

        override suspend fun discoverDevices(windowDays: Int): List<String> = listOf("Pixel Watch")

        override suspend fun readWeightRecords(
            from: Instant,
            to: Instant,
        ): List<DomainWeightRecord> = emptyList()

        override suspend fun readBodyFatRecords(
            from: Instant,
            to: Instant,
        ): List<DomainBodyFatRecord> = emptyList()

        override suspend fun readBloodPressureRecords(
            from: Instant,
            to: Instant,
        ): List<DomainBloodPressureRecord> = emptyList()

        override suspend fun readOxygenSaturationRecords(
            from: Instant,
            to: Instant,
        ): List<DomainOxygenSaturationRecord> = emptyList()

        private companion object {
            val sleepStart: Instant = Instant.parse("2026-06-28T22:00:00Z")
            val sleepEnd: Instant = Instant.parse("2026-06-29T06:00:00Z")
            val workoutStart: Instant = Instant.parse("2026-06-29T09:00:00Z")
            val workoutEnd: Instant = Instant.parse("2026-06-29T10:00:00Z")

            val sleepSession =
                DomainSleepSessionRecord(
                    id = "sleep-1",
                    startTime = sleepStart,
                    endTime = sleepEnd,
                    startZoneOffsetSeconds = 0,
                    endZoneOffsetSeconds = 0,
                    deviceName = "Pixel Watch",
                    stages =
                        listOf(
                            DomainSleepStage(
                                startTime = sleepStart,
                                endTime = sleepStart.plusSeconds(3 * 60 * 60),
                                stageType = DomainSleepStageType.LIGHT,
                            ),
                            DomainSleepStage(
                                startTime = sleepStart.plusSeconds(3 * 60 * 60),
                                endTime = sleepStart.plusSeconds(5 * 60 * 60),
                                stageType = DomainSleepStageType.DEEP,
                            ),
                            DomainSleepStage(
                                startTime = sleepStart.plusSeconds(5 * 60 * 60),
                                endTime = sleepEnd,
                                stageType = DomainSleepStageType.REM,
                            ),
                        ),
                )

            val workoutSession =
                DomainExerciseSessionRecord(
                    id = "workout-1",
                    startTime = workoutStart,
                    endTime = workoutEnd,
                    exerciseType = "running",
                    deviceName = "Pixel Watch",
                )

            val heartRateRecords =
                listOf(
                    DomainHeartRateRecord(
                        id = "hr-1",
                        deviceName = "Pixel Watch",
                        samples =
                            listOf(
                                DomainHeartRateSample(
                                    time = sleepStart.plusSeconds(60 * 60),
                                    beatsPerMinute = 52,
                                ),
                            ),
                    ),
                    DomainHeartRateRecord(
                        id = "hr-2",
                        deviceName = "Pixel Watch",
                        samples =
                            listOf(
                                DomainHeartRateSample(
                                    time = workoutStart.plusSeconds(15 * 60),
                                    beatsPerMinute = 148,
                                ),
                            ),
                    ),
                    DomainHeartRateRecord(
                        id = "hr-3",
                        deviceName = "Pixel Watch",
                        samples =
                            listOf(
                                DomainHeartRateSample(
                                    time = Instant.parse("2026-06-29T14:00:00Z"),
                                    beatsPerMinute = 64,
                                ),
                            ),
                    ),
                )

            val hrvRecord =
                DomainHrvRecord(
                    id = "hrv-1",
                    time = sleepStart.plusSeconds(2 * 60 * 60),
                    rmssdMs = 38.5f,
                    deviceName = "Pixel Watch",
                )
        }
    }
}
