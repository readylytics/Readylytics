package app.readylytics.health.core.healthconnect.data.healthconnect

import app.readylytics.health.core.model.domain.repository.HealthConnectPermissionRevokedException

// On API 34+ (platform-integrated Health Connect) a permission-denied call throws
// android.health.connect.HealthConnectException, which wraps the real SecurityException as its
// cause rather than extending it -- so a plain `catch (e: SecurityException)` misses it and the
// call is treated as a fatal error instead of "permission not granted". Shared by every Health
// Connect call site in this module (module-internal visibility) that needs to tell the two apart.
internal fun Throwable.asHealthConnectSecurityCause(): SecurityException? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 10) {
        if (current is SecurityException) return current
        current = current.cause
        depth++
    }
    return null
}

internal fun rethrowReadFailureOrOriginal(
    recordTypeName: String?,
    e: Exception,
): Nothing {
    val securityCause = e.asHealthConnectSecurityCause()
    if (securityCause != null) {
        throw HealthConnectPermissionRevokedException(
            cause = securityCause,
            operation = "read",
            recordType = recordTypeName,
        )
    }
    throw e
}
