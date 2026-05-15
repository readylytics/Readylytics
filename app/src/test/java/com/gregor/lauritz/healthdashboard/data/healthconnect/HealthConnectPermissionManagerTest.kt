package com.gregor.lauritz.healthdashboard.data.healthconnect

import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.domain.repository.PermissionStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectPermissionManagerTest {
    private val criticalSet =
        setOf(
            "android.permission.health.READ_SLEEP",
            "android.permission.health.READ_HEART_RATE",
            "android.permission.health.READ_HEART_RATE_VARIABILITY",
            "android.permission.health.READ_EXERCISE",
            "android.permission.health.READ_STEPS",
        )

    private fun manager(status: PermissionStatus): HealthConnectPermissionManager {
        val repo: HealthConnectRepository =
            mockk(relaxed = true) {
                every { criticalPermissions } returns criticalSet
                every { requiredPermissions } returns criticalSet
                coEvery { checkPermissions() } returns status
            }
        return HealthConnectPermissionManager(repo)
    }

    @Test
    fun `initial state is Unknown`() {
        val mgr = manager(PermissionStatus.Granted)
        assertEquals(HealthConnectPermissionState.Unknown, mgr.state.value)
    }

    @Test
    fun `checkPermissions returns Granted when all required granted`() =
        runTest {
            val mgr = manager(PermissionStatus.Granted)
            val state = mgr.checkPermissions()
            assertEquals(HealthConnectPermissionState.Granted, state)
            assertTrue(mgr.revokedPermissions().isEmpty())
            assertFalse(mgr.isSyncDisabled())
        }

    @Test
    fun `checkPermissions returns Revoked when all critical missing`() =
        runTest {
            val mgr = manager(PermissionStatus.Missing(criticalSet))
            val state = mgr.checkPermissions()
            assertEquals(HealthConnectPermissionState.Revoked, state)
            assertTrue(mgr.isSyncDisabled())
        }

    @Test
    fun `checkPermissions returns PartiallyRevoked with missing list`() =
        runTest {
            val missing = setOf("android.permission.health.READ_HEART_RATE")
            val mgr = manager(PermissionStatus.Missing(missing))
            val state = mgr.checkPermissions()
            assertTrue(state is HealthConnectPermissionState.PartiallyRevoked)
            assertEquals(missing.toList(), (state as HealthConnectPermissionState.PartiallyRevoked).missing)
            assertTrue(mgr.isSyncDisabled())
        }

    @Test
    fun `onPermissionRevoked marks single record and updates state`() {
        val mgr = manager(PermissionStatus.Granted)
        mgr.onPermissionRevoked("android.permission.health.READ_HEART_RATE")
        assertFalse(mgr.hasPermission("android.permission.health.READ_HEART_RATE"))
        assertTrue(mgr.hasPermission("android.permission.health.READ_SLEEP"))
        val state = mgr.state.value
        assertTrue(state is HealthConnectPermissionState.PartiallyRevoked)
    }

    @Test
    fun `onPermissionRevoked for all critical types flips to Revoked`() {
        val mgr = manager(PermissionStatus.Granted)
        criticalSet.forEach(mgr::onPermissionRevoked)
        assertEquals(HealthConnectPermissionState.Revoked, mgr.state.value)
    }

    @Test
    fun `re-granting permissions via checkPermissions clears revoked state`() =
        runTest {
            // Start with revoked state
            val repo: HealthConnectRepository =
                mockk(relaxed = true) {
                    every { criticalPermissions } returns criticalSet
                    every { requiredPermissions } returns criticalSet
                    coEvery { checkPermissions() } returnsMany
                        listOf(
                            PermissionStatus.Missing(setOf("android.permission.health.READ_SLEEP")),
                            PermissionStatus.Granted,
                        )
                }
            val mgr = HealthConnectPermissionManager(repo)
            mgr.checkPermissions()
            assertTrue(mgr.isSyncDisabled())
            mgr.checkPermissions()
            assertEquals(HealthConnectPermissionState.Granted, mgr.state.value)
            assertTrue(mgr.revokedPermissions().isEmpty())
            assertFalse(mgr.isSyncDisabled())
        }

    @Test
    fun `Unavailable SDK reports Unknown not Revoked`() =
        runTest {
            val mgr = manager(PermissionStatus.Unavailable)
            val state = mgr.checkPermissions()
            assertEquals(HealthConnectPermissionState.Unknown, state)
            assertFalse(mgr.isSyncDisabled())
        }
}
