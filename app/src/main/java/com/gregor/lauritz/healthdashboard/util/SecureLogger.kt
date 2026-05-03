package com.gregor.lauritz.healthdashboard.util

import android.util.Log
import com.gregor.lauritz.healthdashboard.BuildConfig

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
        if (BuildConfig.DEBUG) {
            Log.d(TAG, event)
        }
    }
    
    /**
     * Log an error without leaking sensitive data.
     * Include error context but not health data.
     */
    fun error(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        // In production: send to crash reporting with non-sensitive details only
        reportToCrashlytics(message, throwable)
    }
    
    private fun reportToCrashlytics(message: String, throwable: Throwable?) {
        // TODO: Integrate Crashlytics or similar crash reporting
    }
}
