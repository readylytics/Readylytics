package com.gregor.lauritz.healthdashboard.data.healthconnect

/**
 * Coarse-grained permission state across all Health Connect record types the
 * app reads. Surfaces the result of [HealthConnectPermissionManager.checkPermissions]
 * so the UI can show a single banner ("Grant permissions") rather than
 * inspecting each record type individually.
 *
 * Record-level revocation is still tracked per-type in [HealthConnectPermissionManager];
 * this sealed class is just the aggregated view.
 */
sealed class HealthConnectPermissionState {
    /** All required record types are accessible. */
    data object Granted : HealthConnectPermissionState()

    /** Every required record type has been revoked by the user. */
    data object Revoked : HealthConnectPermissionState()

    /**
     * The permission status has not been queried in this session yet
     * (typically pre-first-sync). Treated as not-granted for safety.
     */
    data object Unknown : HealthConnectPermissionState()

    /**
     * Some, but not all, of the required record types have been revoked.
     *
     * @param missing fully-qualified Health Connect permission strings (e.g.
     *  "android.permission.health.READ_HEART_RATE") that are NOT currently granted.
     */
    data class PartiallyRevoked(
        val missing: List<String>,
    ) : HealthConnectPermissionState()
}
