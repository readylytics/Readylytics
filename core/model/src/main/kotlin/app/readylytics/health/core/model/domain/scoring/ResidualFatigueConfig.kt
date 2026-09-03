package app.readylytics.health.core.model.domain.scoring

import app.readylytics.health.core.model.data.preferences.SettingsDefaults

/**
 * Validated residual-fatigue parameters handed to the scoring use case.
 *
 * The invariant this type exists to hold: `halfLifeHours` and `fatigueGain` are always finite.
 * `halfLifeMs = halfLifeHours * 3_600_000` is a divisor in the decay term, so a non-finite (or
 * zero) half-life produces `NaN`, which would be persisted into `daily_summaries` and survive into
 * the backup JSON. [UserPreferences][app.readylytics.health.core.model.data.preferences.UserPreferences]
 * is a plain data class any caller can build with an out-of-range value, so the guard belongs here
 * rather than only in the DataStore mappers.
 *
 * Callers reading stored preferences should use [clamped], which coerces rather than throws: a bad
 * stored pref degrades to the nearest valid value instead of failing a day's recompute.
 */
data class ResidualFatigueConfig(
    val halfLifeHours: Float = SettingsDefaults.RESIDUAL_FATIGUE_HALF_LIFE_HOURS,
    val fatigueGain: Float = SettingsDefaults.RESIDUAL_FATIGUE_GAIN,
) {
    init {
        require(halfLifeHours.isFinite() && fatigueGain.isFinite()) {
            "Residual fatigue parameters must be finite " +
                "(halfLifeHours=$halfLifeHours, fatigueGain=$fatigueGain)"
        }
    }

    companion object {
        /**
         * Builds a config from stored preferences, coercing each parameter into its validated
         * range. A non-finite stored value cannot be coerced meaningfully and falls back to the
         * shipped default.
         */
        fun clamped(
            halfLifeHours: Float,
            fatigueGain: Float,
        ): ResidualFatigueConfig =
            ResidualFatigueConfig(
                halfLifeHours =
                    coerceOrDefault(
                        value = halfLifeHours,
                        min = SettingsDefaults.MIN_RESIDUAL_FATIGUE_HALF_LIFE_HOURS,
                        max = SettingsDefaults.MAX_RESIDUAL_FATIGUE_HALF_LIFE_HOURS,
                        fallback = SettingsDefaults.RESIDUAL_FATIGUE_HALF_LIFE_HOURS,
                    ),
                fatigueGain =
                    coerceOrDefault(
                        value = fatigueGain,
                        min = SettingsDefaults.MIN_RESIDUAL_FATIGUE_GAIN,
                        max = SettingsDefaults.MAX_RESIDUAL_FATIGUE_GAIN,
                        fallback = SettingsDefaults.RESIDUAL_FATIGUE_GAIN,
                    ),
            )

        private fun coerceOrDefault(
            value: Float,
            min: Float,
            max: Float,
            fallback: Float,
        ): Float = if (value.isFinite()) value.coerceIn(min, max) else fallback
    }
}
