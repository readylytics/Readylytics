package app.readylytics.health.core.scoring.domain.scoring.sleep

import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepModifierResolver

import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.repository.SleepSessionData
import app.readylytics.health.domain.repository.SleepSessionRepository
import app.readylytics.health.domain.repository.SleepStageData
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyRepository
import app.readylytics.health.core.model.domain.security.EncryptionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

private const val MINUTE = 60_000L

class SleepModifierResolverTest {
    private val date = LocalDate.of(2026, 1, 10)
    private val preferences = UserPreferences()

    private val prefetched =
        listOf(
            SleepSessionData(
                id = "prefetched-session",
                deviceName = null,
                startTime = 1_700_000_000_000L,
                endTime = 1_700_006_400_000L,
                durationMinutes = 400,
                efficiency = 90f,
                deepSleepMinutes = 90,
                lightSleepMinutes = 180,
                remSleepMinutes = 90,
                awakeMinutes = 10,
            ),
        )

    private fun awake(startMin: Long, endMin: Long) =
        SleepStageData(
            stageType = "AWAKE",
            startTime = startMin * MINUTE,
            endTime = endMin * MINUTE,
            durationMinutes = (endMin - startMin).toInt(),
        )

    private fun light(startMin: Long, endMin: Long) =
        SleepStageData(
            stageType = "LIGHT",
            startTime = startMin * MINUTE,
            endTime = endMin * MINUTE,
            durationMinutes = (endMin - startMin).toInt(),
        )

    private fun resolverWith(
        stages: List<SleepStageData>,
        regularity: Float?,
    ): SleepModifierResolver {
        val sessionRepo = object : SleepSessionRepository {
            override fun observeSince(fromMs: Long): Flow<List<SleepSessionData>> = emptyFlow()
            override suspend fun getSince(fromMs: Long): List<SleepSessionData> = emptyList()
            override suspend fun countSince(fromMs: Long): Int = 0
            override fun observeSessionStages(sessionId: String): Flow<List<SleepStageData>> = emptyFlow()
            override suspend fun getSessionStages(sessionId: String): List<SleepStageData> = stages
            override fun observeFirstSessionEndingInRange(
                fromMs: Long,
                toMs: Long,
            ): Flow<SleepSessionData?> = emptyFlow()
        }

        val circadianRepo = mockk<CircadianConsistencyRepository>()
        coEvery { circadianRepo.scoreFor(date, any()) } returns regularity

        return SleepModifierResolver(sessionRepo, circadianRepo)
    }

    private fun resolverThrowingRegularity(
        stages: List<SleepStageData>,
    ): SleepModifierResolver {
        val sessionRepo = object : SleepSessionRepository {
            override fun observeSince(fromMs: Long): Flow<List<SleepSessionData>> = emptyFlow()
            override suspend fun getSince(fromMs: Long): List<SleepSessionData> = emptyList()
            override suspend fun countSince(fromMs: Long): Int = 0
            override fun observeSessionStages(sessionId: String): Flow<List<SleepStageData>> = emptyFlow()
            override suspend fun getSessionStages(sessionId: String): List<SleepStageData> = stages
            override fun observeFirstSessionEndingInRange(
                fromMs: Long,
                toMs: Long,
            ): Flow<SleepSessionData?> = emptyFlow()
        }

        val circadianRepo = mockk<CircadianConsistencyRepository>()
        coEvery { circadianRepo.scoreFor(date, any()) } throws RuntimeException("Regularity resolution failed")

        return SleepModifierResolver(sessionRepo, circadianRepo)
    }

    @Test
    fun `suspicious stages suppress fragmentation but keep regularity`() =
        runTest {
            val resolver = resolverWith(stages = listOf(awake(0, 30), light(30, 400)), regularity = 80f)

            val modifiers = resolver.resolve("session", date, preferences, stagesSuspicious = true)

            assertNull(modifiers.fragmentation)
            assertEquals(80f, modifiers.regularityScore!!, 0.01f)
        }

    @Test
    fun `missing stages yield null fragmentation`() =
        runTest {
            val resolver = resolverWith(stages = emptyList(), regularity = null)

            val modifiers = resolver.resolve("session", date, preferences, stagesSuspicious = false)

            assertNull(modifiers.fragmentation)
            assertNull(modifiers.regularityScore)
        }

    @Test
    fun `regularity failure degrades to null instead of throwing`() =
        runTest {
            val resolver = resolverThrowingRegularity(stages = listOf(light(0, 400)))

            val modifiers = resolver.resolve("session", date, preferences, stagesSuspicious = false)

            assertNull(modifiers.regularityScore)
            assertEquals(0f, modifiers.fragmentation!!.wasoMinutes, 0.01f)
        }

    @Test
    fun `resolve forwards prefetched sessions to circadian regularity`() =
        runTest {
            val sessionRepo =
                object : SleepSessionRepository {
                    override fun observeSince(fromMs: Long): Flow<List<SleepSessionData>> = emptyFlow()
                    override suspend fun getSince(fromMs: Long): List<SleepSessionData> = emptyList()
                    override suspend fun countSince(fromMs: Long): Int = 0
                    override fun observeSessionStages(sessionId: String): Flow<List<SleepStageData>> = emptyFlow()
                    override suspend fun getSessionStages(sessionId: String): List<SleepStageData> = emptyList()
                    override fun observeFirstSessionEndingInRange(
                        fromMs: Long,
                        toMs: Long,
                    ): Flow<SleepSessionData?> = emptyFlow()
                }
            val circadianRepo = mockk<CircadianConsistencyRepository>()
            coEvery { circadianRepo.scoreFor(date, preferences, prefetched) } returns 77f
            val resolver =
                SleepModifierResolver(
                    sleepSessionRepository = sessionRepo,
                    circadianConsistencyRepository = circadianRepo,
                )

            val modifiers =
                resolver.resolve(
                    sessionId = "session",
                    targetDate = date,
                    prefs = preferences,
                    stagesSuspicious = false,
                    prefetchedSessions = prefetched,
                )

            assertEquals(77f, modifiers.regularityScore)
            coVerify {
                circadianRepo.scoreFor(date, preferences, prefetched)
            }
        }
}
