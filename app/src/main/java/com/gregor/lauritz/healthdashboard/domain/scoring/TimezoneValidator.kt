package com.gregor.lauritz.healthdashboard.domain.scoring

import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Phase 0.5 — detect rapid timezone changes (travel) vs. ordinary DST
 * transitions so we can:
 *  - group daily summaries by *local* date rather than UTC date, and
 *  - disable physiology metrics that depend on local clock alignment
 *    (e.g. late-nadir penalty) when the user just travelled.
 *
 * Pure, side-effect-free; consumers persist the result (e.g.
 * `lastKnownTimezoneOffsetSeconds` in DataStore).
 */
@Singleton
class TimezoneValidator
    @Inject
    constructor() {
        /** Outcome bucket returned by [validateTimezoneOffset]. */
        enum class TimezoneValidation {
            /** Offsets identical or within tolerance — nothing changed. */
            OK,

            /** Offset shifted by more than [DST_DELTA_TOLERANCE_SECONDS] (~1h). */
            TIMEZONE_JUMP,

            /**
             * Offset shifted by exactly ±3600s on a known DST boundary date
             * (US/EU March / Nov). The user did NOT travel — just clocks moved.
             */
            DST_TRANSITION,
        }

        /**
         * @param currentOffsetSeconds offset of the session being inspected.
         * @param previousOffsetSeconds offset of the most recent prior session.
         * @param sessionDate local date of the current session (used to detect
         *   DST boundaries). Pass null to skip DST detection.
         */
        fun validateTimezoneOffset(
            currentOffsetSeconds: Int?,
            previousOffsetSeconds: Int?,
            sessionDate: LocalDate? = null,
        ): TimezoneValidation {
            if (currentOffsetSeconds == null || previousOffsetSeconds == null) return TimezoneValidation.OK
            val delta = abs(currentOffsetSeconds - previousOffsetSeconds)
            if (delta == 0) return TimezoneValidation.OK

            // Exactly ±3600s shift on a DST boundary date → DST transition.
            if (delta == DST_DELTA_TOLERANCE_SECONDS && sessionDate != null && isDstBoundary(sessionDate)) {
                return TimezoneValidation.DST_TRANSITION
            }
            if (delta >= TIMEZONE_JUMP_THRESHOLD_SECONDS) return TimezoneValidation.TIMEZONE_JUMP
            return TimezoneValidation.OK
        }

        /**
         * Convert a UTC [Instant] into a [LocalDate] using the supplied stored
         * `startZoneOffsetSeconds`. Falls back to system default when offset is
         * null (best-effort, pre-Phase-0.5 data).
         */
        fun localDate(
            instantMs: Long,
            zoneOffsetSeconds: Int?,
        ): LocalDate {
            val offset =
                zoneOffsetSeconds
                    ?.let { ZoneOffset.ofTotalSeconds(it) }
                    ?: ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(instantMs))
            return Instant.ofEpochMilli(instantMs).atOffset(offset).toLocalDate()
        }

        /**
         * Rough DST-boundary detector covering the US and EU schedules:
         *  - US: 2nd Sunday of March, 1st Sunday of November.
         *  - EU: last Sunday of March, last Sunday of October.
         */
        private fun isDstBoundary(date: LocalDate): Boolean {
            if (date.dayOfWeek != java.time.DayOfWeek.SUNDAY) return false
            val month = date.month
            val day = date.dayOfMonth
            return when (month) {
                Month.MARCH -> day in 8..14 || day in 25..31 // US 2nd Sun, EU last Sun
                Month.OCTOBER -> day in 25..31 // EU last Sun
                Month.NOVEMBER -> day in 1..7 // US 1st Sun
                else -> false
            }
        }

        companion object {
            /** Any offset delta ≥ 1 hour is treated as either DST (when on boundary) or a TZ jump. */
            const val DST_DELTA_TOLERANCE_SECONDS = 3600

            /**
             * A delta strictly greater than this counts as a true cross-region jump.
             * Distinct from [ScoringConstants.TIMEZONE_JUMP_THRESHOLD_SECONDS] (used
             * elsewhere for late-nadir suppression) — we re-anchor on 1h here so
             * even a single timezone of travel is detected.
             */
            const val TIMEZONE_JUMP_THRESHOLD_SECONDS = 3600
        }
    }
