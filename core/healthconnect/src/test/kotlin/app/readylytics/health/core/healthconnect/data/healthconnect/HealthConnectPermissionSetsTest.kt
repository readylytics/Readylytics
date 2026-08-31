package app.readylytics.health.core.healthconnect.data.healthconnect

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertTrue

/**
 * `HealthPermissionsRequestContract.createIntent` throws
 * `IllegalArgumentException("Unsupported health connect permission")` for any string that does not
 * start with `android.permission.health.`. Every set we hand to a permission launcher must satisfy
 * that, otherwise the app crashes the moment the request is fired.
 */
class HealthConnectPermissionSetsTest {
    private val context = mockk<Context>(relaxed = true)
    private val ioDispatcher = Dispatchers.Unconfined
    private val repo =
        HealthConnectRepositoryImpl(
            context = context,
            ioDispatcher = ioDispatcher,
            stepRecordReader = StepRecordReader(context = context, ioDispatcher = ioDispatcher),
            intervalTotalsReader = IntervalTotalsReader(context = context, ioDispatcher = ioDispatcher),
            clock = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneId.of("UTC")),
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
}
