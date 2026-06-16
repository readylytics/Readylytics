package app.readylytics.health.domain.validation

private const val MIN_FACTOR = 0.1f
private const val MAX_FACTOR = 0.3f

class RasScalingFactorRule(
    override val errorMessage: String = "RAS: 0.1–0.3",
) : FloatRangeRule(MIN_FACTOR, MAX_FACTOR, errorMessage)
