package app.readylytics.health.data.healthconnect

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import kotlin.test.assertTrue

/**
 * `HealthPermissionsRequestContract.createIntent` throws
 * `IllegalArgumentException("Unsupported health connect permission")` for any string that does not
 * start with `android.permission.health.`. Every set we hand to a permission launcher must satisfy
 * that, otherwise the app crashes the moment the request is fired.
 */
class HealthConnectPermissionSetsTest {
    private val repo =
        HealthConnectRepositoryImpl(
            context = mockk<Context>(relaxed = true),
            ioDispatcher = Dispatchers.Unconfined,
        )

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
}
