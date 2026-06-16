package app.readylytics.health.domain.scoring.components

import java.time.LocalDate

data class AuditTrail(
    val configHashCode: Int,
    val phaseName: String,
    val appliedAt: LocalDate,
    val appliedSf: Float? = null,
    val physiologyProfile: String? = null,
    val rasTotalPre: Float? = null,
    val rasTotalPost: Float? = null,
)
