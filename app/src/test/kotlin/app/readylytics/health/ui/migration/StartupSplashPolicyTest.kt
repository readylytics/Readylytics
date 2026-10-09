package app.readylytics.health.ui.migration

import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupSplashPolicyTest {
    @Test
    fun `splash stays up while readiness is still being checked`() {
        assertTrue(keep(readiness = DatabaseReadiness.Checking))
    }

    @Test
    fun `splash stays up until key validation completes`() {
        assertTrue(keep(isKeyValidationComplete = false, readiness = DatabaseReadiness.Ready))
    }

    @Test
    fun `splash is released once the database is ready`() {
        assertFalse(keep(readiness = DatabaseReadiness.Ready))
    }

    @Test
    fun `splash is released for states that need a visible screen`() {
        listOf(
            DatabaseReadiness.EncryptionRequired,
            DatabaseReadiness.MigrationRequired(6),
            DatabaseReadiness.KeyCorrupted,
            DatabaseReadiness.InsufficientSpace(requiredBytes = 10, availableBytes = 1),
            DatabaseReadiness.Failed("boom"),
        ).forEach { readiness ->
            assertFalse("$readiness must not hide behind the splash", keep(readiness = readiness))
        }
    }

    @Test
    fun `a slow readiness check is bounded by the max wait`() {
        assertTrue(keep(readiness = DatabaseReadiness.Checking, elapsedMs = MAX_WAIT_MS - 1))
        assertFalse(keep(readiness = DatabaseReadiness.Checking, elapsedMs = MAX_WAIT_MS))
        assertFalse(
            keep(isKeyValidationComplete = false, readiness = DatabaseReadiness.Checking, elapsedMs = MAX_WAIT_MS),
        )
    }

    private fun keep(
        isKeyValidationComplete: Boolean = true,
        readiness: DatabaseReadiness,
        elapsedMs: Long = 0L,
    ): Boolean = shouldKeepStartupSplash(isKeyValidationComplete, readiness, elapsedMs, MAX_WAIT_MS)

    private companion object {
        const val MAX_WAIT_MS = 2_000L
    }
}
