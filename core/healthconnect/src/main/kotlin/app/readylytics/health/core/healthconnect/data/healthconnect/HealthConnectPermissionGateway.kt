package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import app.readylytics.health.core.healthconnect.BuildConfig
import app.readylytics.health.core.model.di.IoDispatcher
import app.readylytics.health.core.model.domain.repository.PermissionStatus
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.model.domain.util.logI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

@Singleton
class HealthConnectPermissionGateway
    @Inject
    constructor(
        private val availability: HealthConnectAvailability,
        private val client: HealthConnectClient,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        val criticalPermissions: Set<String> =
            setOf(
                HealthPermission.getReadPermission(SleepSessionRecord::class),
                HealthPermission.getReadPermission(HeartRateRecord::class),
                HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
                HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            )

        val requiredPermissions: Set<String>
            get() {
                val base = criticalPermissions
                return if (availability.isHistoryReadAvailable()) {
                    base + setOf("android.permission.health.READ_HEALTH_DATA_HISTORY")
                } else {
                    base
                }
            }

        val optionalPermissions: Set<String> =
            setOf(
                HealthPermission.getReadPermission(StepsRecord::class),
                HealthPermission.getReadPermission(WeightRecord::class),
                HealthPermission.getReadPermission(BodyFatRecord::class),
                HealthPermission.getReadPermission(BloodPressureRecord::class),
                HealthPermission.getReadPermission(OxygenSaturationRecord::class),
                HealthPermission.getReadPermission(BodyTemperatureRecord::class),
                // The recording app writes a workout's distance and elevation gain as separate
                // records, not on the ExerciseSessionRecord. Without these the app can only
                // integrate the GPS polyline, which reads ~1-3% short of the source app.
                HealthPermission.getReadPermission(DistanceRecord::class),
                HealthPermission.getReadPermission(ElevationGainedRecord::class),
                HealthPermission.getReadPermission(Vo2MaxRecord::class),
                // READ_EXERCISE_ROUTES is deliberately absent. Health Connect does not expose routes
                // in the bulk data-type permission sheet -- it lives under "Additional access"
                // (alongside background and past-data access) as a tri-state Always allow / Ask every
                // time / Don't allow, defaulting to "Ask every time". Requesting it here is silently
                // dropped: on a clean install every other permission comes back USER_SET while routes
                // comes back with no user decision at all. Routes are obtained per workout through
                // ExerciseRouteRequestContract instead (see ui/health/ExerciseRoutePermissionRequest).
                // It must stay declared in AndroidManifest.xml -- that declaration is what makes the
                // "Access exercise routes" row appear in Health Connect settings.
            )

        // Snapshot: evaluated once at construction, not re-queried when the history feature status changes.
        val allPermissions: Set<String> =
            requiredPermissions + optionalPermissions

        val backgroundReadPermission: String =
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

        suspend fun checkPermissions(): PermissionStatus =
            withContext(ioDispatcher) {
                logD(
                    "HealthConnectRepository",
                ) { "Checking permissions..." }
                if (!availability.isAvailable()) {
                    logD(
                        "HealthConnectRepository",
                    ) { "SDK not available" }
                    return@withContext PermissionStatus.Unavailable
                }
                val granted =
                    try {
                        client.permissionController.getGrantedPermissions()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logE("HealthConnectRepository", e) {
                            "Failed to get granted permissions"
                        }
                        throw e
                    }
                if (BuildConfig.DEBUG) {
                    logD(
                        "HealthConnectRepository",
                    ) { "Granted permissions: $granted" }
                    logD("HealthConnectRepository") {
                        "Required permissions: $requiredPermissions"
                    }
                } else {
                    logD(
                        "HealthConnectRepository",
                    ) { "Granted permissions count: ${granted.size}" }
                }

                if (granted.containsAll(requiredPermissions)) {
                    logD(
                        "HealthConnectRepository",
                    ) { "All required permissions granted" }
                    PermissionStatus.Granted
                } else {
                    val missing = requiredPermissions - granted
                    if (BuildConfig.DEBUG) {
                        logI(
                            "HealthConnectRepository",
                        ) { "Missing permissions: $missing" }
                    } else {
                        logD(
                            "HealthConnectRepository",
                        ) { "Missing permissions count: ${missing.size}" }
                    }
                    PermissionStatus.Missing(missing)
                }
            }

        suspend fun hasReadPermission(
            recordType: KClass<out Record>,
            label: String,
        ): Boolean =
            withContext(ioDispatcher) {
                if (!availability.isAvailable()) return@withContext false
                try {
                    client.permissionController
                        .getGrantedPermissions()
                        .contains(HealthPermission.getReadPermission(recordType))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logE("HealthConnectRepository", e) {
                        "Failed to check $label permission"
                    }
                    false
                }
            }

        suspend fun hasExerciseRoutesPermission(): Boolean =
            withContext(ioDispatcher) {
                if (!availability.isAvailable()) return@withContext false
                try {
                    val granted = client.permissionController.getGrantedPermissions()
                    granted.contains("android.permission.health.READ_EXERCISE_ROUTES") ||
                        granted.contains("com.google.android.apps.healthdata.permission.READ_EXERCISE_ROUTES")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logE("HealthConnectRepository", e) {
                        "Failed to check exercise routes permission"
                    }
                    false
                }
            }
    }
