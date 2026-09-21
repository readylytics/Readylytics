package app.readylytics.health.core.scoring.domain.scoring.sleep

import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepModifierResolver

import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import app.readylytics.health.core.model.domain.repository.SleepSessionRepository
import app.readylytics.health.core.model.domain.repository.SleepStageData
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

    private fun awake(
        startMin: Long,
        endMin: Long,
        sessionId: String? = null,
    ) = SleepStageData(
        stageType = "AWAKE",
        startTime = startMin * MINUTE,
        endTime = endMin * MINUTE,
        durationMinutes = (endMin - startMin).toInt(),
        sessionId = sessionId,
    )

    private fun light(
        startMin: Long,
        endMin: Long,
        sessionId: String? = null,
    ) = SleepStageData(
        stageType = "LIGHT",
        startTime = startMin * MINUTE,
        endTime = endMin * MINUTE,
        durationMinutes = (endMin - startMin).toInt(),
        sessionId = sessionId,
    )

    private fun fakeSessionRepo(
        stages: List<SleepStageData>,
        stagesBySessionIds: Map<Set<String>, List<SleepStageData>> = emptyMap(),
    ): SleepSessionRepository =
        object : SleepSessionRepository {
            override fun observeSince(fromMs: Long): Flow<List<SleepSessionData>> = emptyFlow()

            override suspend fun getSince(fromMs: Long): List<SleepSessionData> = emptyList()

            override suspend fun getInRange(
                fromMs: Long,
                toMs: Long,
            ): List<SleepSessionData> = emptyList()

            override suspend fun countSince(fromMs: Long): Int = 0

            override fun observeSessionStages(sessionId: String): Flow<List<SleepStageData>> = emptyFlow()

            override suspend fun getSessionStages(sessionId: String): List<SleepStageData> = stages

            override suspend fun getSessionStages(sessionIds: List<String>): List<SleepStageData> =
                stagesBySessionIds[sessionIds.toSet()] ?: stages

            override fun observeFirstSessionEndingInRange(
                fromMs: Long,
                toMs: Long,
            ): Flow<SleepSessionData?> = emptyFlow()
        }

    private fun resolverWith(
        stages: List<SleepStageData>,
        regularity: Float?,
        stagesBySessionIds: Map<Set<String>, List<SleepStageData>> = emptyMap(),
    ): SleepModifierResolver {
        val sessionRepo = fakeSessionRepo(stages, stagesBySessionIds)

        val circadianRepo = mockk<CircadianConsistencyRepository>()
        coEvery { circadianRepo.scoreFor(date, any()) } returns regularity

        return SleepModifierResolver(sessionRepo, circadianRepo)
    }

    private fun resolverThrowingRegularity(stages: List<SleepStageData>): SleepModifierResolver {
        val sessionRepo = fakeSessionRepo(stages)

        val circadianRepo = mockk<CircadianConsistencyRepository>()
        coEvery { circadianRepo.scoreFor(date, any()) } throws RuntimeException("Regularity resolution failed")

        return SleepModifierResolver(sessionRepo, circadianRepo)
    }

    @Test
    fun `suspicious stages suppress fragmentation but keep regularity`() =
        runTest {
            val resolver = resolverWith(stages = listOf(awake(0, 30), light(30, 400)), regularity = 80f)

            val modifiers = resolver.resolve(setOf("session"), date, preferences, stagesSuspicious = true)

            assertNull(modifiers.fragmentation)
            assertEquals(80f, modifiers.regularityScore!!, 0.01f)
        }

    @Test
    fun `missing stages yield null fragmentation`() =
        runTest {
            val resolver = resolverWith(stages = emptyList(), regularity = null)

            val modifiers = resolver.resolve(setOf("session"), date, preferences, stagesSuspicious = false)

            assertNull(modifiers.fragmentation)
            assertNull(modifiers.regularityScore)
        }

    @Test
    fun `regularity failure degrades to null instead of throwing`() =
        runTest {
            val resolver = resolverThrowingRegularity(stages = listOf(light(0, 400)))

            val modifiers = resolver.resolve(setOf("session"), date, preferences, stagesSuspicious = false)

            assertNull(modifiers.regularityScore)
            assertEquals(0f, modifiers.fragmentation!!.wasoMinutes, 0.01f)
        }

    @Test
    fun `resolve forwards prefetched sessions to circadian regularity`() =
        runTest {
            val sessionRepo = fakeSessionRepo(stages = emptyList())
            val circadianRepo = mockk<CircadianConsistencyRepository>()
            coEvery { circadianRepo.scoreFor(date, preferences, prefetched) } returns 77f
            val resolver =
                SleepModifierResolver(
                    sleepSessionRepository = sessionRepo,
                    circadianConsistencyRepository = circadianRepo,
                )

            val modifiers =
                resolver.resolve(
                    coreSessionIds = setOf("session"),
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

    // ─── WP-14/C4: multi-session-id core scoping ────────────────────────────────────────────

    @Test
    fun `resolve fetches stages for every core session id, not just one`() =
        runTest {
            val coreIds = setOf("core-1", "core-2")
            val stagesForBoth =
                listOf(
                    light(0, 180, sessionId = "core-1"),
                    awake(180, 190, sessionId = "core-1"),
                    light(190, 400, sessionId = "core-2"),
                )
            val resolver =
                resolverWith(
                    stages = emptyList(),
                    regularity = null,
                    stagesBySessionIds = mapOf(coreIds to stagesForBoth),
                )

            val modifiers = resolver.resolve(coreIds, date, preferences, stagesSuspicious = false)

            // A single-ID fetch (the pre-C4 call path) would have seen only one segment's stages
            // and reported a shorter/incorrect WASO window; the merged fetch sees the full core.
            assertEquals(10f, modifiers.fragmentation!!.wasoMinutes, 0.01f)
        }

    @Test
    fun `resolve deduplicates identical stage rows synced under more than one session id`() =
        runTest {
            val coreIds = setOf("core-1", "core-2")
            val duplicated =
                listOf(
                    light(0, 180, sessionId = "core-1"),
                    awake(180, 190, sessionId = "core-1"),
                    // Same interval duplicated verbatim under a second session id -- must collapse
                    // to a single awakening, not double-count WASO.
                    awake(180, 190, sessionId = "core-2"),
                    light(190, 400, sessionId = "core-2"),
                )
            val resolver =
                resolverWith(
                    stages = emptyList(),
                    regularity = null,
                    stagesBySessionIds = mapOf(coreIds to duplicated),
                )

            val modifiers = resolver.resolve(coreIds, date, preferences, stagesSuspicious = false)

            assertEquals(10f, modifiers.fragmentation!!.wasoMinutes, 0.01f)
            assertEquals(1, modifiers.fragmentation!!.awakeningCount)
        }
}
