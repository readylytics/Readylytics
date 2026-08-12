package app.readylytics.health.domain.airecommendation

import app.readylytics.health.domain.model.RecoveryFlag

/**
 * Stable plain-English glosses for every [RecoveryFlag], aligned with Section F of
 * `internal-docs/ai-recommendations/DAILY_PROMPT_TEMPLATE.md`. This file is pure Kotlin:
 * a `when` over [RecoveryFlag] keeps it exhaustive, so a newly added enum value fails
 * compilation until a gloss exists.
 */
object RecoveryFlagGlossary {
    fun explain(flag: RecoveryFlag): String =
        when (flag) {
            RecoveryFlag.OVERREACHING ->
                "training load has risen faster than your fitness can currently absorb"
            RecoveryFlag.STRONG_RECOVERY_SIGNAL ->
                "your recovery markers are notably above your personal norm today"
            RecoveryFlag.ILLNESS_ONSET ->
                "your overnight recovery signals are unusually different from your normal baseline"
            RecoveryFlag.NADIR_DELAYED ->
                "your overnight HRV low point arrived later than usual"
            RecoveryFlag.CALIBRATING ->
                "your personal baselines are still being established with limited data"
            RecoveryFlag.HRV_MISSING ->
                "no reliable heart rate variability data was available for this day"
            RecoveryFlag.STAGES_MISSING ->
                "sleep-stage data was missing or unreliable, so sleep architecture carries less weight"
            RecoveryFlag.WORKOUT_IMPACT ->
                "yesterday's demanding session is still showing up in your recovery markers"
            RecoveryFlag.REST_DAY_SUCCESS ->
                "yesterday's rest day is showing up as a recovery benefit today"
            RecoveryFlag.REST_DAY_NO_IMPACT ->
                "yesterday's rest day did not yet shift your recovery markers"
            RecoveryFlag.SUSPICIOUS_STAGE_RATIO ->
                "the deep/REM sleep split looks unreliable, so treat sleep architecture with caution"
        }
}
