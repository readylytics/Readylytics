package app.readylytics.health.domain.model

enum class SleepStageType(
    val value: String,
) {
    DEEP("DEEP"),
    REM("REM"),
    LIGHT("LIGHT"),
    AWAKE("AWAKE"),
    UNKNOWN("UNKNOWN"),
}
