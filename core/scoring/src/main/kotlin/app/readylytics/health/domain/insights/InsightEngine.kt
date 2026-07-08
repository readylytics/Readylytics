package app.readylytics.health.domain.insights

/**
 * Registry/pipeline for deterministic insight rules. New rules can be
 * registered here without modifying the evaluation logic.
 */
object InsightEngine {
    private val rules: List<InsightRule> =
        listOf(
            CircadianShiftRecoveryMissRule(),
            LoadSpikeRecoveryStrainRule(),
            HighStrainSleepDeficitRule(),
            LateNadirShortSleepRule(),
            HrvDropLowSpo2Rule(),
            LateNadirElevatedRhrRule(),
            BpElevatedHighStrainRule(),
            RasDepletionHighStrainRule(),
            HrvDeclineStreakRule(),
            StepShortfallRule(),
            RasWeeklyUnderperformanceRule(),
            WeightDriftTrainingLoadRule(),
            HrvMissingCaveatRule(),
            SuspiciousStageRatioCaveatRule(),
        )

    fun evaluate(context: InsightContext): List<InsightFinding> = rules.mapNotNull { it.evaluate(context) }
}
