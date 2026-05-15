package com.gregor.lauritz.healthdashboard.domain.validation

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Cross-field validator for heart-rate zone configuration.
 *
 * Invalid zones (e.g. zone1Min > zone1Max, zone5Max < zone4Max, or zones above the
 * estimated HR max) silently corrupt TRIMP and load-score calculations. This
 * validator surfaces those problems early and proposes corrected values.
 *
 * Rules:
 *  1. Each zone must be a valid bpm range (min ≤ max).
 *  2. Zones must be monotonically increasing: zone1Max < zone2Max < … < zone5Max.
 *  3. zone1Min must be ≥ resting heart rate (RHR baseline).
 *  4. zone5Max must be ≤ 0.95 × estimatedHrMax (anything higher implies a typo).
 *
 * Estimated HR max uses the Karvonen (220 − age) formula by default; for older
 * users (≥ 60) the Tanaka (208 − 0.7·age) approximation is more accurate.
 *
 * REF: Karvonen 1957; Tanaka 2001 J Am Coll Cardiol; Janssen 2001 HR Reserve.
 */
@Singleton
class ZoneConfigValidator
    @Inject
    constructor() {
        /**
         * Input shape covering all configurable zone thresholds in bpm + supporting context.
         */
        data class ZoneConfig(
            val zone1MinBpm: Int,
            val zone1MaxBpm: Int,
            val zone2MaxBpm: Int,
            val zone3MaxBpm: Int,
            val zone4MaxBpm: Int,
            /** Optional zone5 upper bound; defaults to estimatedHrMax when null. */
            val zone5MaxBpm: Int? = null,
            val ageYears: Int,
            val rhrBaselineBpm: Int? = null,
            val explicitHrMax: Int? = null,
        )

        data class ZoneIssue(
            val field: String,
            val message: String,
            /** Suggested corrected value (bpm); may be null when no clean correction exists. */
            val suggestedValue: Int? = null,
        )

        data class ZoneValidation(
            val isValid: Boolean,
            val issues: List<ZoneIssue>,
            /** Effective HR max used during validation (explicit or estimated). */
            val effectiveHrMax: Int,
            /** Timestamp the validation was run (millis since epoch). */
            val timestampMs: Long = System.currentTimeMillis(),
        )

        fun validate(config: ZoneConfig): ZoneValidation {
            val issues = mutableListOf<ZoneIssue>()
            val hrMax = config.explicitHrMax ?: estimateHrMax(config.ageYears)

            // 1. Zone 1 internal bounds
            if (config.zone1MinBpm > config.zone1MaxBpm) {
                issues +=
                    ZoneIssue(
                        field = "zone1MinBpm",
                        message = "Zone 1 min (${config.zone1MinBpm}) exceeds Zone 1 max (${config.zone1MaxBpm}).",
                        suggestedValue = config.zone1MaxBpm - 1,
                    )
            }

            // 2. zone1Min ≥ RHR baseline
            config.rhrBaselineBpm?.let { rhr ->
                if (config.zone1MinBpm < rhr) {
                    issues +=
                        ZoneIssue(
                            field = "zone1MinBpm",
                            message =
                                "Zone 1 min (${config.zone1MinBpm}) below resting heart rate ($rhr). " +
                                    "Zone 1 floor should be at least your RHR.",
                            suggestedValue = rhr,
                        )
                }
            }

            // 3. Monotonic zone progression
            val ordered =
                listOf(
                    "zone1MaxBpm" to config.zone1MaxBpm,
                    "zone2MaxBpm" to config.zone2MaxBpm,
                    "zone3MaxBpm" to config.zone3MaxBpm,
                    "zone4MaxBpm" to config.zone4MaxBpm,
                )
            for (i in 1 until ordered.size) {
                if (ordered[i].second <= ordered[i - 1].second) {
                    issues +=
                        ZoneIssue(
                            field = ordered[i].first,
                            // Localized message format: see strings.xml zone_error_ordering
                            message =
                                "${ordered[i].first} (${ordered[i].second}) must be higher than " +
                                    "${ordered[i - 1].first} (${ordered[i - 1].second}).",
                            suggestedValue = ordered[i - 1].second + 5,
                        )
                }
            }

            // 4. Zone 5 cap relative to HR max — only check when user explicitly set zone5.
            val maxAllowed = (hrMax * MAX_ZONE5_FRACTION_OF_HRMAX).roundToInt()
            config.zone5MaxBpm?.let { zone5 ->
                if (zone5 > maxAllowed) {
                    issues +=
                        ZoneIssue(
                            field = "zone5MaxBpm",
                            // Localized message format: see strings.xml zone_error_zone5_exceeds_max
                            message =
                                "Zone 5 max ($zone5) exceeds 95% of your estimated HR max ($hrMax). " +
                                    "Lower by ${zone5 - maxAllowed} bpm.",
                            suggestedValue = maxAllowed,
                        )
                }
            }

            // 5. Zone 4 should not exceed HR max either (catches obvious typos)
            if (config.zone4MaxBpm > hrMax) {
                issues +=
                    ZoneIssue(
                        field = "zone4MaxBpm",
                        message =
                            "Zone 4 max (${config.zone4MaxBpm}) exceeds your estimated HR max ($hrMax bpm). " +
                                "Lower by ${config.zone4MaxBpm - hrMax + 5} bpm?",
                        suggestedValue = hrMax - 5,
                    )
            }

            return ZoneValidation(
                isValid = issues.isEmpty(),
                issues = issues,
                effectiveHrMax = hrMax,
            )
        }

        /**
         * Re-validate zones after an age change. Returns the suggested corrections if any
         * zone now exceeds the recomputed HR max.
         */
        fun revalidateOnAgeChange(
            config: ZoneConfig,
            newAge: Int,
        ): ZoneValidation = validate(config.copy(ageYears = newAge))

        /**
         * Estimate HR max from age. Uses Tanaka (more accurate ≥ 40 y) when older;
         * Karvonen 220 − age otherwise.
         * REF: Tanaka 2001; Karvonen 1957.
         */
        fun estimateHrMax(age: Int): Int =
            if (age >= TANAKA_AGE_THRESHOLD) {
                (208 - 0.7f * age).roundToInt()
            } else {
                220 - age
            }

        companion object {
            const val MAX_ZONE5_FRACTION_OF_HRMAX = 0.95f
            const val TANAKA_AGE_THRESHOLD = 40
        }
    }
