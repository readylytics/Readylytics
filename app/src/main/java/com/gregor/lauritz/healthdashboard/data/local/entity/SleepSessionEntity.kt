package com.gregor.lauritz.healthdashboard.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sleep_sessions",
    indices = [
        Index(value = ["startTime"]),
        Index(value = ["endTime"]),
    ],
)
data class SleepSessionEntity(
    @PrimaryKey val id: String,
    val startTime: Long,
    val endTime: Long,
    val durationMinutes: Int,
    val efficiency: Float,
    val deepSleepMinutes: Int,
    val remSleepMinutes: Int,
    val lightSleepMinutes: Int,
    val awakeMinutes: Int,
    val sleepScore: Float? = null,
    val startZoneOffsetSeconds: Int? = null,
    val endZoneOffsetSeconds: Int? = null,
    val deviceName: String? = null,
    /**
     * Optional human-readable warning produced by [com.gregor.lauritz.healthdashboard.domain.scoring.SleepArchitectureValidator]
     * when the stage architecture was flagged as suspicious or invalid (Phase 0.3).
     */
    val stageValidationWarning: String? = null,
    /**
     * Normalised device source label used to apply device-specific stage corrections
     * (e.g. "fitbit", "oura", "apple_watch", "garmin"). Currently equal to
     * [deviceName] in lowercase; kept as a separate column so future ingestion
     * pipelines can map differently without changing the user-visible name.
     */
    val deviceSource: String? = null,
    /**
     * True iff [SleepArchitectureValidator.isSuspiciousArchitecture] returned true
     * for this night. Persisted so historical UI can render the flag without
     * re-running validation.
     */
    val stagesSuspicious: Boolean = false,
    /**
     * True iff [SleepArchitectureValidator.isValidArchitecture] returned false
     * for this night, meaning the night should NOT contribute to baselines.
     */
    val stagesInvalid: Boolean = false,
    /**
     * Phase 0.5 — true when the session's `startZoneOffsetSeconds` differs from
     * the previous session by more than 1h AND the date is NOT a DST boundary.
     * Persisted so downstream consumers can disable nadir-based penalties.
     */
    val timezoneJumpDetected: Boolean = false,
    /**
     * Phase 0.5 — true when the session's offset shifted by exactly ±3600s on
     * a known DST boundary date.
     */
    val dstTransitionDetected: Boolean = false,
)
