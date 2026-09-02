package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.database.domain.scoring.TrainingReadinessProjectionRecomputeUseCase
import app.readylytics.health.core.model.domain.sync.*
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.scoring.TrainingReadinessConfig
import app.readylytics.health.core.model.domain.util.RetentionBounds
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

class FullHistoricalResyncUseCaseTest {
    private val settingsRepo = mockk<SettingsRepository>()
    private val healthSyncUseCase = mockk<HealthSyncUseCase>()
    private val trainingReadinessProjectionRecomputeUseCase = mockk<TrainingReadinessProjectionRecomputeUseCase>()
    private val clock = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneId.of("UTC"))
    private val useCase =
        FullHistoricalResyncUseCase(
            settingsRepo,
            healthSyncUseCase,
            trainingReadinessProjectionRecomputeUseCase,
            clock = clock,
        )

    private val today = LocalDate.ofInstant(clock.instant(), ZoneId.of("UTC"))

    @Test
    fun `resolveScoringToday uses stored scoring timezone`() {
        val instant = Instant.parse("2026-07-20T00:30:00Z")

        assertEquals(
            LocalDate.of(2026, 7, 20),
            resolveScoringToday(UserPreferences(scoringZoneId = "Europe/Berlin"), instant),
        )
        assertEquals(
            LocalDate.of(2026, 7, 19),
            resolveScoringToday(UserPreferences(scoringZoneId = "America/Los_Angeles"), instant),
        )
    }

    @Test
    fun `enabled retention resyncs from today minus retentionDays to today`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(UserPreferences(retentionDaysEnabled = true, retentionDays = 365))
            val startSlot = slot<LocalDate>()
            val endSlot = slot<LocalDate>()
            coEvery {
                healthSyncUseCase.resyncRange(capture(startSlot), capture(endSlot), any(), any())
            } returns Result.success(Unit)

            useCase.execute()

            assertEquals(today.minusDays(365), startSlot.captured)
            assertEquals(today, endSlot.captured)
        }

    @Test
    fun `disabled retention resyncs the full absolute-max window`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(UserPreferences(retentionDaysEnabled = false, retentionDays = 365))
            val startSlot = slot<LocalDate>()
            coEvery {
                healthSyncUseCase.resyncRange(capture(startSlot), any(), any(), any())
            } returns Result.success(Unit)

            useCase.execute()

            assertEquals(today.minusDays(RetentionBounds.ABSOLUTE_MAX_DAYS), startSlot.captured)
        }

    @Test
    fun `recomputeOnly delegates to recomputeRange instead of resyncRange`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(UserPreferences(retentionDaysEnabled = true, retentionDays = 365))
            val startSlot = slot<LocalDate>()
            val endSlot = slot<LocalDate>()
            coEvery {
                healthSyncUseCase.recomputeRange(capture(startSlot), capture(endSlot), any())
            } returns Result.success(Unit)

            useCase.execute(recomputeOnly = true)

            assertEquals(today.minusDays(365), startSlot.captured)
            assertEquals(today, endSlot.captured)
            coVerify(exactly = 0) { healthSyncUseCase.resyncRange(any(), any(), any(), any()) }
        }

    @Test
    fun `recomputeOnly with a range override narrows the recompute to that range`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(UserPreferences(retentionDaysEnabled = true, retentionDays = 365))
            val startSlot = slot<LocalDate>()
            val endSlot = slot<LocalDate>()
            coEvery {
                healthSyncUseCase.recomputeRange(capture(startSlot), capture(endSlot), any())
            } returns Result.success(Unit)

            useCase.execute(
                recomputeOnly = true,
                rangeOverride = ScoreInvalidation.AffectedRange(today.minusDays(10), today.minusDays(5)),
            )

            assertEquals(today.minusDays(10), startSlot.captured)
            assertEquals(today.minusDays(5), endSlot.captured)
        }

    @Test
    fun `recomputeOnly range override is clamped to the retention window`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(UserPreferences(retentionDaysEnabled = true, retentionDays = 30))
            val startSlot = slot<LocalDate>()
            val endSlot = slot<LocalDate>()
            coEvery {
                healthSyncUseCase.recomputeRange(capture(startSlot), capture(endSlot), any())
            } returns Result.success(Unit)

            // Override reaches further back than the 30-day retention window and past today.
            useCase.execute(
                recomputeOnly = true,
                rangeOverride = ScoreInvalidation.AffectedRange(today.minusDays(365), today.plusDays(10)),
            )

            assertEquals(today.minusDays(30), startSlot.captured)
            assertEquals(today, endSlot.captured)
        }

    @Test
    fun `a range override is ignored for a full (non-recomputeOnly) resync`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(UserPreferences(retentionDaysEnabled = true, retentionDays = 365))
            val startSlot = slot<LocalDate>()
            val endSlot = slot<LocalDate>()
            coEvery {
                healthSyncUseCase.resyncRange(capture(startSlot), capture(endSlot), any(), any())
            } returns Result.success(Unit)

            useCase.execute(
                recomputeOnly = false,
                rangeOverride = ScoreInvalidation.AffectedRange(today.minusDays(10), today.minusDays(5)),
            )

            assertEquals(today.minusDays(365), startSlot.captured)
            assertEquals(today, endSlot.captured)
        }

    @Test
    fun `delegates failure from the underlying resync`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(UserPreferences(retentionDaysEnabled = true, retentionDays = 200))
            coEvery { healthSyncUseCase.resyncRange(any(), any(), any(), any()) } returns
                Result.failure("boom", "RESYNC_ERROR")

            val result = useCase.execute()

            assert(result is Result.Failure)
            coVerify { healthSyncUseCase.resyncRange(any(), any(), any(), any()) }
        }

    @Test
    fun `recomputeOnly with an inverted clamped range returns success without recomputing`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(UserPreferences(retentionDaysEnabled = true, retentionDays = 30))

            val result =
                useCase.execute(
                    recomputeOnly = true,
                    rangeOverride = ScoreInvalidation.AffectedRange(today.minusDays(60), today.minusDays(45)),
                )

            assertEquals(Result.success(Unit), result)
            coVerify(exactly = 0) { healthSyncUseCase.recomputeRange(any(), any(), any()) }
            coVerify(exactly = 0) { healthSyncUseCase.resyncRange(any(), any(), any(), any()) }
        }

    /**
     * [HealthSyncUseCase.withSyncLock] just runs the passed suspend block and returns its result --
     * stub it that way so `executeTrainingReadinessProjection` tests exercise the real delegation
     * instead of hanging on an unstubbed mock.
     */
    private fun stubWithSyncLockToRunBlock() {
        coEvery { healthSyncUseCase.withSyncLock<Result<Unit>>(any()) } coAnswers {
            firstArg<suspend () -> Result<Unit>>().invoke()
        }
    }

    @Test
    fun `executeTrainingReadinessProjection clamps the retained range through RetentionBounds`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(UserPreferences(retentionDaysEnabled = true, retentionDays = 200))
            stubWithSyncLockToRunBlock()
            val startSlot = slot<LocalDate>()
            val endSlot = slot<LocalDate>()
            coEvery {
                trainingReadinessProjectionRecomputeUseCase.execute(
                    capture(startSlot),
                    capture(endSlot),
                    any(),
                    any(),
                    any(),
                )
            } returns Result.success(Unit)

            useCase.executeTrainingReadinessProjection(TrainingReadinessConfig.fromStored(40f, 0.7f))

            assertEquals(today.minusDays(200), startSlot.captured)
            assertEquals(today, endSlot.captured)
        }

    @Test
    fun `executeTrainingReadinessProjection never touches Health Connect sync`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(UserPreferences(retentionDaysEnabled = true, retentionDays = 200))
            stubWithSyncLockToRunBlock()
            coEvery {
                trainingReadinessProjectionRecomputeUseCase.execute(any(), any(), any(), any(), any())
            } returns Result.success(Unit)

            useCase.executeTrainingReadinessProjection(TrainingReadinessConfig.fromStored(40f, 0.7f))

            coVerify(exactly = 0) { healthSyncUseCase.recomputeRange(any(), any(), any()) }
            coVerify(exactly = 0) { healthSyncUseCase.resyncRange(any(), any(), any(), any()) }
            coVerify(exactly = 0) { healthSyncUseCase.sync(any(), any()) }
        }

    @Test
    fun `executeTrainingReadinessProjection passes the exact requested config through`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(UserPreferences(retentionDaysEnabled = true, retentionDays = 200))
            stubWithSyncLockToRunBlock()
            val configSlot = slot<TrainingReadinessConfig>()
            coEvery {
                trainingReadinessProjectionRecomputeUseCase.execute(
                    any(),
                    any(),
                    any(),
                    capture(configSlot),
                    any(),
                )
            } returns Result.success(Unit)

            val requested = TrainingReadinessConfig.fromStored(100f, 0.9f)
            useCase.executeTrainingReadinessProjection(requested)

            assertEquals(requested, configSlot.captured)
        }

    @Test
    fun `executeTrainingReadinessProjection delegates failure from the projection use case`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(UserPreferences(retentionDaysEnabled = true, retentionDays = 200))
            stubWithSyncLockToRunBlock()
            coEvery {
                trainingReadinessProjectionRecomputeUseCase.execute(any(), any(), any(), any(), any())
            } returns Result.failure("boom", "PROJECTION_ERROR")

            val result = useCase.executeTrainingReadinessProjection(TrainingReadinessConfig.fromStored(40f, 0.7f))

            assert(result is Result.Failure)
        }

    @Test
    fun `executeTrainingReadinessProjection serializes the projection through withSyncLock`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(UserPreferences(retentionDaysEnabled = true, retentionDays = 200))
            var lockHeldDuringProjection = false
            coEvery { healthSyncUseCase.withSyncLock<Result<Unit>>(any()) } coAnswers {
                lockHeldDuringProjection = true
                try {
                    firstArg<suspend () -> Result<Unit>>().invoke()
                } finally {
                    lockHeldDuringProjection = false
                }
            }
            coEvery {
                trainingReadinessProjectionRecomputeUseCase.execute(any(), any(), any(), any(), any())
            } coAnswers {
                // Assert from inside the delegate call that it actually runs while the lock is held,
                // not merely that both were invoked in some order.
                assertEquals(true, lockHeldDuringProjection)
                Result.success(Unit)
            }

            val result = useCase.executeTrainingReadinessProjection(TrainingReadinessConfig.fromStored(40f, 0.7f))

            assertEquals(Result.success(Unit), result)
            coVerify(exactly = 1) { healthSyncUseCase.withSyncLock<Result<Unit>>(any()) }
            coVerify(exactly = 1) {
                trainingReadinessProjectionRecomputeUseCase.execute(any(), any(), any(), any(), any())
            }
        }
}

