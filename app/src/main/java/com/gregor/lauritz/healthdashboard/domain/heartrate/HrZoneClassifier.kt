package com.gregor.lauritz.healthdashboard.domain.heartrate

import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences

object HrZoneClassifier {
    /**
     * Returns zone 0 (below zone 1), 1–4, or 5 (above zone 4).
     * Zone 0 is resting/recovery; zones 1-5 match user-configured BPM ranges.
     */
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

    fun zoneName(zone: Int): String =
        when (zone) {
            0 -> "Below Z1"
            5 -> "Zone 5"
            else -> "Zone $zone"
        }
}
