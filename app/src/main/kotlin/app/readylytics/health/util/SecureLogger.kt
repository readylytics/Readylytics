package app.readylytics.health.util

import app.readylytics.health.domain.util.logD
import app.readylytics.health.domain.util.logE

/**
 * Logging utility that prevents leakage of sensitive health data.
 * Use this instead of Log.* for any health-related information.
 */
object SecureLogger {
    private const val TAG = "HealthDashboard"

    /**
     * Log a debug message without sensitive data.
     * Use descriptive messages instead of logging actual values.
     */
    fun debugEvent(event: String) {
        logD(TAG) { event }
    }

    /**
     * Log an error without leaking sensitive data.
     * Include error context but not health data.
     */
    fun error(
        message: String,
        throwable: Throwable? = null,
    ) {
        logE(TAG, throwable) { message }
    }
}
