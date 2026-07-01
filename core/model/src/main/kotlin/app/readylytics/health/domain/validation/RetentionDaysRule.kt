package app.readylytics.health.domain.validation

private const val MIN_DAYS = 90
private const val MAX_DAYS = 1800

class RetentionDaysRule(
    override val errorMessage: String = "Months: 3–60",
) : ValidationRule<String> by IntRangeRule(MIN_DAYS, MAX_DAYS, errorMessage)
