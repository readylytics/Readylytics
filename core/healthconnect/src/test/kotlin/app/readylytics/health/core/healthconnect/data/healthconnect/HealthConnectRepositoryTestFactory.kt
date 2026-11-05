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
): HealthConnectRepositoryImpl =
    HealthConnectRepositoryImpl(
        context = context,
        ioDispatcher = ioDispatcher,
        stepRecordReader = StepRecordReader(context = context, ioDispatcher = ioDispatcher, client = client),
        intervalTotalsReader = IntervalTotalsReader(context = context, ioDispatcher = ioDispatcher, client = client),
        clock = clock,
        client = client,
        routeLookup = routeLookup,
    )
