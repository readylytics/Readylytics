package app.readylytics.health

import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.data.preferences.SettingsRepository
import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesPrewarmerTest {
    private val settingsRepository = mockk<SettingsRepository>()
    private val settingsRepositoryLazy = mockk<Lazy<SettingsRepository>>()

    @Test
    fun `prewarm resolves the lazy and collects exactly one value without hanging`() =
        runTest {
            var collections = 0
            every { settingsRepositoryLazy.get() } returns settingsRepository
            every { settingsRepository.userPreferences } returns
                flow {
                    collections += 1
                    emit(UserPreferences())
                    // A flow that never completes after its first emission must not hang
                    // prewarm(): first() short-circuits collection as soon as one value lands.
                    awaitCancellation()
                }
            val prewarmer = PreferencesPrewarmer(settingsRepositoryLazy)

            prewarmer.prewarm()

            verify(exactly = 1) { settingsRepositoryLazy.get() }
            assertEquals(1, collections)
        }

    @Test
    fun `failure from the user preferences flow is swallowed`() =
        runTest {
            every { settingsRepositoryLazy.get() } returns settingsRepository
            every { settingsRepository.userPreferences } returns
                flow { throw IllegalStateException("user preferences read failed") }
            val prewarmer = PreferencesPrewarmer(settingsRepositoryLazy)

            prewarmer.prewarm()

            verify(exactly = 1) { settingsRepositoryLazy.get() }
        }

    @Test
    fun `failure resolving the lazy settings repository is swallowed`() =
        runTest {
            every { settingsRepositoryLazy.get() } throws IllegalStateException("lazy resolution failed")
            val prewarmer = PreferencesPrewarmer(settingsRepositoryLazy)

            prewarmer.prewarm()

            verify(exactly = 1) { settingsRepositoryLazy.get() }
        }

    @Test
    fun `cancellation from the user preferences flow propagates instead of being swallowed`() =
        runTest {
            every { settingsRepositoryLazy.get() } returns settingsRepository
            every { settingsRepository.userPreferences } returns
                flow { throw CancellationException("user preferences collection cancelled") }
            val prewarmer = PreferencesPrewarmer(settingsRepositoryLazy)

            var caught: CancellationException? = null
            try {
                prewarmer.prewarm()
            } catch (e: CancellationException) {
                caught = e
            }

            assertTrue(caught != null)
        }
}
