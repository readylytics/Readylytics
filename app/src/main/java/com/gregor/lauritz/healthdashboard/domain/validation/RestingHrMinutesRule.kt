package com.gregor.lauritz.healthdashboard.domain.validation

private const val MIN_MINUTES = 0
private const val MAX_MINUTES = 60

class RestingHrMinutesRule(
    override val errorMessage: String = "Minutes: 0–60",
) : ValidationRule<String> by IntRangeRule(MIN_MINUTES, MAX_MINUTES, errorMessage)
