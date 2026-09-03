package app.readylytics.health.core.model.domain.scoring

import app.readylytics.health.core.model.data.preferences.SettingsDefaults

/**
 * Validated Training Readiness parameters derived from stored preferences.
 *
 * Persisted values are deliberately not rounded to UI slider stops. Restores can preserve any
 * finite in-range value while the UI independently limits newly selected scale values to its
 * five-unit increments.
 *
 * Callers reading preferences must use [fromStored], which repairs corrupt values before this
 * validated type is created. Direct construction rejects invalid values so the scorer cannot
 * receive a configuration that would produce a non-finite projection.
 */
data class TrainingReadinessConfig(
    val residualFatigueScale: Float,
    val loadBalanceWeight: Float,
) {
    init {
        require(
            residualFatigueScale.isFinite() &&
                residualFatigueScale in
                SettingsDefaults.MIN_TRAINING_READINESS_RESIDUAL_FATIGUE_SCALE..
                SettingsDefaults.MAX_TRAINING_READINESS_RESIDUAL_FATIGUE_SCALE,
        ) {
            "Training readiness residual fatigue scale must be finite and within the supported range"
        }
        require(
            loadBalanceWeight.isFinite() &&
                loadBalanceWeight in
                SettingsDefaults.MIN_TRAINING_READINESS_LOAD_BALANCE_WEIGHT..
                SettingsDefaults.MAX_TRAINING_READINESS_LOAD_BALANCE_WEIGHT,
        ) {
            "Training readiness load balance weight must be finite and within the supported range"
        }
    }

    companion object {
        fun fromStored(
            scale: Float,
            weight: Float,
        ): TrainingReadinessConfig =
            TrainingReadinessConfig(
                residualFatigueScale =
                    scale.takeIf(Float::isFinite)
                        ?.coerceIn(
                            SettingsDefaults.MIN_TRAINING_READINESS_RESIDUAL_FATIGUE_SCALE,
                            SettingsDefaults.MAX_TRAINING_READINESS_RESIDUAL_FATIGUE_SCALE,
                        ) ?: SettingsDefaults.TRAINING_READINESS_RESIDUAL_FATIGUE_SCALE,
                loadBalanceWeight =
                    weight.takeIf(Float::isFinite)
                        ?.coerceIn(
                            SettingsDefaults.MIN_TRAINING_READINESS_LOAD_BALANCE_WEIGHT,
                            SettingsDefaults.MAX_TRAINING_READINESS_LOAD_BALANCE_WEIGHT,
                        ) ?: SettingsDefaults.TRAINING_READINESS_LOAD_BALANCE_WEIGHT,
            )
    }
}
