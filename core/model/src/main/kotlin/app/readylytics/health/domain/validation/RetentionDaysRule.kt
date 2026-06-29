package app.readylytics.health.domain.validation

private const val MIN_DAYS = 1
private const val MAX_DAYS = 3650

class RetentionDaysRule(
    override val errorMessage: String = "Days: 1–3,650",
) : ValidationRule<String> by IntRangeRule(MIN_DAYS, MAX_DAYS, errorMessage)
