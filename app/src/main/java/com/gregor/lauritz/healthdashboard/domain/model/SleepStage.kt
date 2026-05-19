package com.gregor.lauritz.healthdashboard.domain.model

enum class SleepStage(
    val label: String,
    val type: String,
) {
    DEEP("Deep", "DEEP"),
    LIGHT("Light", "LIGHT"),
    REM("REM", "REM"),
    AWAKE("Awake", "AWAKE"),
}
