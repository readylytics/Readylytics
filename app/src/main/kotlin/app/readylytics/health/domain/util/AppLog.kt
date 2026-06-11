package app.readylytics.health.domain.util

import android.util.Log
import app.readylytics.health.BuildConfig

inline fun logD(
    tag: String,
    msg: () -> String,
) {
    if (BuildConfig.DEBUG) Log.d(tag, msg())
}

inline fun logE(
    tag: String,
    throwable: Throwable? = null,
    msg: () -> String,
) {
    if (BuildConfig.DEBUG) Log.e(tag, msg(), throwable)
}
