package app.readylytics.health.domain.scoring

import app.readylytics.health.data.preferences.PhysiologyProfile
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.data.security.EncryptionManager
import app.readylytics.health.domain.repository.SleepSessionData
import app.readylytics.health.domain.repository.SleepSessionRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
private const val FOUR_HOURS_MINUTES = 240

class CircadianConsistencyRepositoryTest {
    private val defaultPrefs =
        UserPreferences(
            consistencyThresholdMinutes = 30,
            consistencyEvaluationDays = 7,
            consistencyBaselineDays = 14,
        )

    private fun fakeSleepSession(
        id: String,
        bedHour: Int,
        bedMin: Int = 0,
        wakeHour: Int = 7,
        wakeMin: Int = 0,
        daysAgo: Int = 0,
        anchorDate: LocalDate = LocalDate.now(),
    ): SleepSessionData {
        val midnight = anchorDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val baseMs = midnight - daysAgo * ONE_DAY_MS
        val startMs = baseMs - (24 - bedHour) * 3600_000L - bedMin * 60_000L
        val endMs = baseMs + wakeHour * 3600_000L + wakeMin * 60_000L
        val durationMinutes = ((endMs - startMs) / 60_000L).toInt().coerceAtLeast(FOUR_HOURS_MINUTES)
        return SleepSessionData(
            id = id,
            startTime = startMs,
            endTime = endMs,
            durationMinutes = durationMinutes,
            efficiency = 85f,
            deepSleepMinutes = 90,
            remSleepMinutes = 90,
            lightSleepMinutes = 180,
            awakeMinutes = 10,
            deviceName = "Device",
        )
    }

    private fun buildRepo(
        sessions: List<SleepSessionData>,
        prefs: UserPreferences = defaultPrefs,
        decryptedOverride: Int? = null,
    ): CircadianConsistencyRepository {
        val sessionFlow = MutableStateFlow(sessions)
        val repository = mockk<SleepSessionRepository>()
        every { repository.observeSince(any()) } returns sessionFlow
        val settingsRepo = mockk<SettingsRepository>()
        every { settingsRepo.userPreferences } returns MutableStateFlow(prefs)
        val encryptionManager = mockk<EncryptionManager>()
        every { encryptionManager.decrypt(any()) } returns decryptedOverride?.toString()
        return CircadianConsistencyRepository(repository, settingsRepo, encryptionManager)
    }

    @Test
    fun `emits Calibrating when fewer than MIN_BASELINE_SESSIONS sessions exist`() =
        runTest {
            val repo = buildRepo(sessions = listOf(fakeSleepSession("1", bedHour = 23)))
            val result = repo.resultFor(LocalDate.now()).first()
            assertTrue(result is CircadianConsistencyResult.Calibrating)
        }

    @Test
    fun `emits Calibrating when all sessions are naps under 3 hours`() =
        runTest {
            val naps =
                (1..10).map { i ->
                    SleepSessionData(
                        id = "nap$i",
                        startTime = System.currentTimeMillis() - i * ONE_DAY_MS,
                        endTime = System.currentTimeMillis() - i * ONE_DAY_MS + 2 * 3600_000L,
                        durationMinutes = 120,
                        efficiency = 80f,
                        deepSleepMinutes = 0,
                        remSleepMinutes = 0,
                        lightSleepMinutes = 120,
                        awakeMinutes = 0,
                        deviceName = "NapDevice",
                    )
                }
            val repo = buildRepo(sessions = naps)
            assertTrue(repo.resultFor(LocalDate.now()).first() is CircadianConsistencyResult.Calibrating)
        }

    @Test
    fun `emits Ready with perfect score when schedule is perfectly consistent`() =
        runTest {
            val sessions =
                (0 until 14).map { i ->
                    fakeSleepSession(id = "s$i", bedHour = 23, wakeHour = 7, daysAgo = i)
                }
            val repo = buildRepo(sessions)
            val result = repo.resultFor(LocalDate.now()).first()
            assertTrue("Expected Ready, got $result", result is CircadianConsistencyResult.Ready)
            val ready = result as CircadianConsistencyResult.Ready
            assertEquals(100f, ready.score, 1f)
        }

    @Test
    fun `emits Ready with degraded score when schedule is inconsistent`() =
        runTest {
            // Even nights: bedtime 23:00 / Odd nights: bedtime 01:00 — large deviation
            val sessions =
                (0 until 14).map { i ->
                    val bedHour = if (i % 2 == 0) 23 else 1
                    fakeSleepSession(id = "s$i", bedHour = bedHour, wakeHour = 7, daysAgo = i)
                }
            val repo = buildRepo(sessions)
            val result = repo.resultFor(LocalDate.now()).first()
            assertTrue(result is CircadianConsistencyResult.Ready)
            val ready = result as CircadianConsistencyResult.Ready
            assertTrue("Score should be < 80 for inconsistent schedule, was ${ready.score}", ready.score < 80f)
        }

    private fun consistentSessions() =
        (0 until 14).map { i ->
            fakeSleepSession(id = "s$i", bedHour = 23, wakeHour = 7, daysAgo = i)
        }

    @Test
    fun `threshold derives from physiology profile when no override is set`() =
        runTest {
            // Athlete → 20, Sedentary → 45 (CircadianThresholdDefaults), flat pref left at default.
            val athleteRepo =
                buildRepo(consistentSessions(), defaultPrefs.copy(physiologyProfile = PhysiologyProfile.ATHLETE))
            val athlete = athleteRepo.resultFor(LocalDate.now()).first() as CircadianConsistencyResult.Ready
            assertEquals(20, athlete.thresholdMinutes)

            val sedentaryRepo =
                buildRepo(consistentSessions(), defaultPrefs.copy(physiologyProfile = PhysiologyProfile.SEDENTARY))
            val sedentary = sedentaryRepo.resultFor(LocalDate.now()).first() as CircadianConsistencyResult.Ready
            assertEquals(45, sedentary.thresholdMinutes)
        }

    @Test
    fun `encrypted override wins over the profile default`() =
        runTest {
            val prefs =
                defaultPrefs.copy(
                    physiologyProfile = PhysiologyProfile.ATHLETE,
                    circadianThresholdOverride = "cipher",
                )
            val repo = buildRepo(consistentSessions(), prefs, decryptedOverride = 55)
            val result = repo.resultFor(LocalDate.now()).first() as CircadianConsistencyResult.Ready
            assertEquals(55, result.thresholdMinutes)
        }

    @Test
    fun `legacy non-default flat pref is preserved as an override`() =
        runTest {
            // No encrypted override, but a user who tuned the old flat slider to 45 keeps that intent.
            val prefs =
                defaultPrefs.copy(
                    physiologyProfile = PhysiologyProfile.ATHLETE,
                    consistencyThresholdMinutes = 45,
                )
            val repo = buildRepo(consistentSessions(), prefs)
            val result = repo.resultFor(LocalDate.now()).first() as CircadianConsistencyResult.Ready
            assertEquals(45, result.thresholdMinutes)
        }

    @Test
    fun `emits MissingData when baseline is met but no session for anchorDate exists`() =
        runTest {
            val sessions =
                (1 until 14).map { i ->
                    // Skip today (i=0)
                    fakeSleepSession(id = "s$i", bedHour = 23, wakeHour = 7, daysAgo = i)
                }
            val repo = buildRepo(sessions)
            val result = repo.resultFor(LocalDate.now()).first()
            assertTrue("Expected MissingData, got $result", result is CircadianConsistencyResult.MissingData)
        }
}
