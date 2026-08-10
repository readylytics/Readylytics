package app.readylytics.health.domain.model

enum class LoadContext {
    BELOW_TYPICAL,
    SWEET_SPOT,
    ELEVATED,
    HIGH,
    UNKNOWN
}

fun Float?.toLoadContext(): LoadContext {
    if (this == null || this.isNaN() || this < 0.0f) return LoadContext.UNKNOWN
    return when {
        this < 0.8f -> LoadContext.BELOW_TYPICAL
        this <= 1.3f -> LoadContext.SWEET_SPOT
        this <= 1.5f -> LoadContext.ELEVATED
        else -> LoadContext.HIGH
    }
}
