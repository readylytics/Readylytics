package app.readylytics.health.domain.util

interface ResourceProvider {
    fun getString(resId: Int): String

    fun getString(
        resId: Int,
        vararg formatArgs: Any,
    ): String
}
