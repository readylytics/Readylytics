package com.gregor.lauritz.healthdashboard.domain.scoring.components

data class EmergencyFlagThresholds(
    // OVERREACHING: HRV↑ + RHR↓ indicates functional overreaching
    // REF: Le Meur 2013 Med Sci Sports Exerc; Bellenger 2017 Front Physiol
    val overreachingZHrvThreshold: Float = 1.5f,
    val overreachingZRhrThreshold: Float = -2.0f,

    // ILLNESS: HRV↓ + RHR↑ indicates illness onset or acute stress
    // REF: Mishra 2020 Nat Biomed Eng; Quer 2021 Nat Med
    val illnessZHrvThreshold: Float = -1.5f,
    val illnessZRhrThreshold: Float = 2.0f,
    val illnessRhrDeltaBpm: Float = 5f,
)
