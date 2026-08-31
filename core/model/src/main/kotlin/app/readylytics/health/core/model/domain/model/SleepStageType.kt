package app.readylytics.health.core.model.domain.model

enum class SleepStageType(
    val value: String,
) {
    DEEP("DEEP"),
    REM("REM"),
    LIGHT("LIGHT"),
    AWAKE("AWAKE"),
    UNKNOWN("UNKNOWN"),
}
