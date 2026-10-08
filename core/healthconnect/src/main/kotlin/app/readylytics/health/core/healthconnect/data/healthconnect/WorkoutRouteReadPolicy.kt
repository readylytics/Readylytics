package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseSessionRecord
import app.readylytics.health.core.model.domain.model.RouteState
import app.readylytics.health.core.model.domain.repository.StoredWorkoutRouteSnapshot

/** Both bulk and Changes reads use the same exact stored identity and permission gate. */
internal object WorkoutRouteReadPolicy {
    suspend fun hasConsent(client: HealthConnectClient): Boolean {
        val granted = client.permissionController.getGrantedPermissions()
        return "android.permission.health.READ_EXERCISE_ROUTES" in granted ||
            "com.google.android.apps.healthdata.permission.READ_EXERCISE_ROUTES" in granted
    }

    fun needsRead(record: ExerciseSessionRecord, stored: StoredWorkoutRouteSnapshot?): Boolean =
        stored == null || stored.routeState != RouteState.IMPORTED ||
            stored.id != record.metadata.id ||
            stored.startTime != record.startTime.toEpochMilli() ||
            stored.endTime != record.endTime.toEpochMilli() ||
            stored.exerciseType != record.exerciseType.toString() ||
            stored.deviceName != DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin)
}
