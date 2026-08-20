package app.readylytics.health.core.model.domain.util

interface ResourceProvider {
    fun getString(resId: Int): String

    fun getString(
        resId: Int,
        vararg formatArgs: Any,
    ): String
}
