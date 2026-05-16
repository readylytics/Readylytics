package com.gregor.lauritz.healthdashboard.domain.validation

private const val MIN_STEPS = 0
private const val MAX_STEPS = 100000

class StepGoalRule(
    override val errorMessage: String = "Steps: 0–100,000",
) : ValidationRule<String> by IntRangeRule(MIN_STEPS, MAX_STEPS, errorMessage)
