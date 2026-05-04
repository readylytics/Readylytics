package com.gregor.lauritz.healthdashboard.domain.scoring.components

import java.time.LocalDate

data class AuditTrail(
    val configHashCode: Int,
    val phaseName: String,
    val appliedAt: LocalDate,
    val appliedSf: Float? = null,
    val physiologyProfile: String? = null,
    val paiTotalPre: Float? = null,
    val paiTotalPost: Float? = null,
)
