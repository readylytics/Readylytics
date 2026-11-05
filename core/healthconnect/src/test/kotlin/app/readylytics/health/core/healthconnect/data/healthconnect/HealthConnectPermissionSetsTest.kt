package app.readylytics.health.core.healthconnect.data.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `HealthPermissionsRequestContract.createIntent` throws
 * `IllegalArgumentException("Unsupported health connect permission")` for any string that does not
 * start with `android.permission.health.`. Every set we hand to a permission launcher must satisfy
 * that, otherwise the app crashes the moment the request is fired.
 */
class HealthConnectPermissionSetsTest {
    private val context = mockk<Context>(relaxed = true)
    private val client = mockk<HealthConnectClient>(relaxed = true)
    private val repo =
        createHealthConnectRepositoryForTest(context = context, client = client, routeLookup = mockk(relaxed = true))

    private val permissionPrefix = "android.permission.health."

    @Test
    fun allPermissions_areRequestableByHealthConnectContract() {
        val unsupported = repo.allPermissions.filterNot { it.startsWith(permissionPrefix) }
        assertTrue(unsupported.isEmpty(), "Not requestable via HealthPermissionsRequestContract: $unsupported")
    }

    @Test
    fun optionalPermissions_areRequestableByHealthConnectContract() {
        val unsupported = repo.optionalPermissions.filterNot { it.startsWith(permissionPrefix) }
        assertTrue(unsupported.isEmpty(), "Not requestable via HealthPermissionsRequestContract: $unsupported")
    }

    @Test
    fun requiredPermissions_areRequestableByHealthConnectContract() {
        val unsupported = repo.requiredPermissions.filterNot { it.startsWith(permissionPrefix) }
        assertTrue(unsupported.isEmpty(), "Not requestable via HealthPermissionsRequestContract: $unsupported")
    }

    @Test
    fun backgroundReadPermission_isRequestableByHealthConnectContract() {
        assertTrue(repo.backgroundReadPermission.startsWith(permissionPrefix))
    }

    /**
     * Health Connect files exercise routes under "Additional access", not the data-type list, and
     * silently drops them from a bulk permission request -- the user is never asked. Putting the
     * permission back into a requested set makes onboarding advertise a grant it cannot deliver.
     * Routes are obtained per workout via `ExerciseRouteRequestContract`.
     */
    @Test
    fun exerciseRoutes_isNotRequestedThroughThePermissionSheet() {
        assertTrue(
            repo.allPermissions.none { it.endsWith("READ_EXERCISE_ROUTES") },
            "READ_EXERCISE_ROUTES cannot be granted through the bulk permission sheet",
        )
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    /**
     * `allPermissions` is a snapshot taken once at construction. `requiredPermissions` is a live
     * getter that re-queries the history feature status on every read.
     */
    @Test
    fun allPermissions_isSnapshotAtConstruction() {
        val historyPermission = "android.permission.health.READ_HEALTH_DATA_HISTORY"
        mockkObject(HealthConnectClient)
        every { HealthConnectClient.getSdkStatus(any()) } returns HealthConnectClient.SDK_AVAILABLE
        every { client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) } returns
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        val snapshotRepo = createHealthConnectRepositoryForTest(context, client, mockk(relaxed = true))
        assertTrue(historyPermission in snapshotRepo.allPermissions)
        assertTrue(historyPermission in snapshotRepo.requiredPermissions)

        every { client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) } returns
            HealthConnectFeatures.FEATURE_STATUS_UNAVAILABLE

        assertTrue(historyPermission in snapshotRepo.allPermissions)
        assertFalse(historyPermission in snapshotRepo.requiredPermissions)
    }
}
