package app.readylytics.health.core.database.di

import app.readylytics.health.core.database.data.migration.DatabaseReadinessGate
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.test.assertEquals

class DatabaseProvisionGuardTest {

    @Test
    fun cachedGuardDoesNotInspect() {
        val statesToReject = listOf(
            DatabaseReadiness.Checking,
            DatabaseReadiness.EncryptionRequired,
            DatabaseReadiness.MigrationRequired(5),
            DatabaseReadiness.MigrationRequired(6),
            DatabaseReadiness.Failed("test"),
            DatabaseReadiness.KeyCorrupted,
            DatabaseReadiness.InsufficientSpace(100L, 10L)
        )

        for (state in statesToReject) {
            val gate = mockk<DatabaseReadinessGate>()
            val stateFlow = MutableStateFlow(state)
            every { gate.readiness } returns stateFlow
            every { gate.inspect() } throws AssertionError("inspect() should not be called")

            val exception = assertThrows(IllegalStateException::class.java) {
                requireDatabaseReady(gate)
            }
            assertEquals("HealthDatabase cannot open before the external v7 migration is complete", exception.message)

            verify(exactly = 0) { gate.inspect() }
        }
    }

    @Test
    fun cachedReadyAllowsProvision() {
        val gate = mockk<DatabaseReadinessGate>()
        val stateFlow = MutableStateFlow(DatabaseReadiness.Ready)
        every { gate.readiness } returns stateFlow
        every { gate.inspect() } throws AssertionError("inspect() should not be called")

        requireDatabaseReady(gate) // Should not throw

        verify(exactly = 0) { gate.inspect() }
    }
}
