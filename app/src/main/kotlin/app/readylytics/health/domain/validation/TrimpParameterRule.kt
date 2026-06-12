package app.readylytics.health.domain.validation

class TrimpParameterRule(
    min: Float,
    max: Float,
    override val errorMessage: String,
) : FloatRangeRule(min, max, errorMessage)
