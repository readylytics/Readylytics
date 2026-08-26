package app.readylytics.health.feature.workouts

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutsDistancePermissionGateTest {
    @Test
    fun `granted result is cached and probe is not called again`() =
        runTest {
            var probeCallCount = 0
            val gate =
                WorkoutsDistancePermissionGate {
                    probeCallCount++
                    true
                }

            assertTrue(gate.isGranted())
            assertTrue(gate.isGranted())
            assertTrue(gate.isGranted())
            assertTrue("Probe should only be called once after a granted result", probeCallCount == 1)
        }

    @Test
    fun `denied result is re-probed on every call`() =
        runTest {
            var probeCallCount = 0
            val gate =
                WorkoutsDistancePermissionGate {
                    probeCallCount++
                    false
                }

            assertFalse(gate.isGranted())
            assertFalse(gate.isGranted())
            assertFalse(gate.isGranted())
            assertTrue("Probe should be called on every denied check", probeCallCount == 3)
        }

    @Test
    fun `denied then granted transitions correctly and caches`() =
        runTest {
            var probeCallCount = 0
            var grantPermission = false
            val gate =
                WorkoutsDistancePermissionGate {
                    probeCallCount++
                    grantPermission
                }

            // Initially denied — re-probed each time
            assertFalse(gate.isGranted())
            assertFalse(gate.isGranted())
            assertTrue(probeCallCount == 2)

            // User grants the permission
            grantPermission = true
            assertTrue(gate.isGranted())
            assertTrue(probeCallCount == 3)

            // Now cached — no more probes
            assertTrue(gate.isGranted())
            assertTrue(gate.isGranted())
            assertTrue("Probe should stop after a granted result is cached", probeCallCount == 3)
        }
}
