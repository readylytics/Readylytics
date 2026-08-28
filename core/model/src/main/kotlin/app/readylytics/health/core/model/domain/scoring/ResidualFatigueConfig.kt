package app.readylytics.health.core.model.domain.scoring

data class ResidualFatigueConfig(
    val enabled: Boolean = true,
    val halfLifeHours: Float = 24f,
    val fatigueGain: Float = 1.0f,
)
