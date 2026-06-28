package app.readylytics.health.domain.validation

private const val MIN_HOURS = 1
private const val MAX_HOURS = 24

class SyncIntervalHoursRule(
    override val errorMessage: String = "Hours: 1–24",
) : ValidationRule<String> by IntRangeRule(MIN_HOURS, MAX_HOURS, errorMessage)
