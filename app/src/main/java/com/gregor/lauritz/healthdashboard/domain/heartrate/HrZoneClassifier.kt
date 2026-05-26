package com.gregor.lauritz.healthdashboard.domain.heartrate

import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences

object HrZoneClassifier {
    fun classify(
        bpm: Int,
        prefs: UserPreferences,
    ): Int =
        when {
            bpm < prefs.zone1MinBpm -> 0
            bpm <= prefs.zone1MaxBpm -> 1
            bpm <= prefs.zone2MaxBpm -> 2
            bpm <= prefs.zone3MaxBpm -> 3
            bpm <= prefs.zone4MaxBpm -> 4
            else -> 5
        }
}
