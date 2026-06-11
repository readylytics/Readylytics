package com.gregor.lauritz.healthdashboard.domain.model

enum class InsightType {
    LATE_NADIR,
    SICK_INDICATOR,
    OVERREACHING,
    ;

    companion object {
        fun fromRecoveryFlag(flag: RecoveryFlag): InsightType? =
            when (flag) {
                RecoveryFlag.NADIR_DELAYED -> LATE_NADIR
                RecoveryFlag.ILLNESS_ONSET -> SICK_INDICATOR
                RecoveryFlag.OVERREACHING -> OVERREACHING
                else -> null
            }
    }
}
