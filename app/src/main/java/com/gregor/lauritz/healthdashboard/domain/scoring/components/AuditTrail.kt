package com.gregor.lauritz.healthdashboard.domain.scoring.components

import java.time.LocalDate

data class AuditTrail(
    val configHashCode: Int,
    val phaseName: String,
    val appliedAt: LocalDate,
)
