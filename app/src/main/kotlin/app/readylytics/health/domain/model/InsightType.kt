package app.readylytics.health.domain.model

enum class InsightType {
    LATE_NADIR,
    SICK_INDICATOR,
    OVERREACHING,
    WORKOUT_IMPACT,
    REST_DAY_SUCCESS,
    REST_DAY_NO_IMPACT,
    CIRCADIAN_SHIFT_RECOVERY_MISS,
    HIGH_STRAIN_SLEEP_DEFICIT,
    LATE_NADIR_SHORT_SLEEP,
    ;

    companion object {
        fun fromRecoveryFlag(flag: RecoveryFlag): InsightType? =
            when (flag) {
                RecoveryFlag.NADIR_DELAYED -> LATE_NADIR
                RecoveryFlag.ILLNESS_ONSET -> SICK_INDICATOR
                RecoveryFlag.OVERREACHING -> OVERREACHING
                RecoveryFlag.WORKOUT_IMPACT -> WORKOUT_IMPACT
                RecoveryFlag.REST_DAY_SUCCESS -> REST_DAY_SUCCESS
                RecoveryFlag.REST_DAY_NO_IMPACT -> REST_DAY_NO_IMPACT
                else -> null
            }
    }
}
