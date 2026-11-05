package app.readylytics.health.core.healthconnect.data.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import app.readylytics.health.core.model.domain.repository.WorkoutRouteLookup
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Single construction seam for [HealthConnectRepositoryImpl] in unit tests. Tests must build the
 * repository through this function so that a change to the production constructor only needs to be
 * reflected here.
 */
internal fun createHealthConnectRepositoryForTest(
    context: Context,
    client: HealthConnectClient,
    routeLookup: WorkoutRouteLookup,
    ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    clock: Clock = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneId.of("UTC")),
): HealthConnectRepositoryImpl {
    val availability = HealthConnectAvailability(context = context, client = client)
    val permissionGateway =
        HealthConnectPermissionGateway(availability = availability, client = client, ioDispatcher = ioDispatcher)
    val pagedReader = HealthConnectPagedReader(client = client)
    val readGuard =
        HealthConnectReadGuard(
            availability = availability,
            permissions = permissionGateway,
            ioDispatcher = ioDispatcher,
        )
    return HealthConnectRepositoryImpl(
        stepRecordReader = StepRecordReader(context = context, ioDispatcher = ioDispatcher, client = client),
        clock = clock,
        availability = availability,
        permissionGateway = permissionGateway,
        pagedReader = pagedReader,
        readGuard = readGuard,
        exerciseReader =
            HealthConnectExerciseReader(
                client = client,
                ioDispatcher = ioDispatcher,
                availability = availability,
                permissions = permissionGateway,
                pagedReader = pagedReader,
                guard = readGuard,
                intervalTotalsReader =
                    IntervalTotalsReader(context = context, ioDispatcher = ioDispatcher, client = client),
                routeLookup = routeLookup,
            ),
    )
}
