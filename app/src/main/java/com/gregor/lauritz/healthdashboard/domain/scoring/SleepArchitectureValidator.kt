package com.gregor.lauritz.healthdashboard.domain.scoring

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Validates the deep / REM fractions reported by a wearable for a single night
 * against age-banded population norms (Ohayon 2004 Sleep 27:1255) and applies
 * empirical device-specific corrections published in the consumer-wearable
 * validation literature.
 *
 * Replaces the previous hard-coded global thresholds in [ScoringConstants].
 *
 * Citations:
 * - Ohayon MM et al. *Meta-Analysis of Quantitative Sleep Parameters From Childhood to Old Age in Healthy Individuals*. Sleep 2004;27(7):1255-1273.
 * - Boulos MI et al. *Normal polysomnographic values for adults aged 18 to 79 years*. Lancet Respir Med 2019;7(6):533-543.
 * - de Zambotti M et al. *Wearable Sleep Technology in Clinical and Research Settings*. Med Sci Sports Exerc 2019;51(7):1538-1557.
 * - Chinoy ED et al. *Performance of seven consumer sleep-tracking devices compared with polysomnography*. Sleep 2021;44(5):zsaa291.
 */
@Singleton
class SleepArchitectureValidator
    @Inject
    constructor() {
        /** Result of inspecting a night's deep/REM percentages. */
        data class Result(
            /** False = night should be skipped from scoring; True = usable. */
            val valid: Boolean,
            /** True = within 70-75% confidence band; flag the user but keep scoring. */
            val suspicious: Boolean,
            /** Human-readable explanation for the UI when valid=false or suspicious=true. */
            val warning: String?,
        )

        /**
         * Hard upper bound on deep% / rem% beyond which we reject the night entirely.
         * "Hard" because no PSG cohort reports values above these for healthy adults;
         * any wearable claiming a 50% REM night is almost certainly mis-classifying
         * stages.
         *
         * @property suspiciousFractionOfCeiling deep% or rem% above this fraction
         *  of their respective ceiling triggers the suspicious flag (still scored,
         *  reduced arch weight). 0.85 ≈ "within 15% of the hard ceiling".
         */
        data class Bounds(
            val deepMaxFraction: Float,
            val remMaxFraction: Float,
            val suspiciousFractionOfCeiling: Float = 0.85f,
        )

        /**
         * Age-banded upper bounds per Ohayon 2004 PSG meta-analysis with a small
         * safety margin to account for measurement noise.
         */
        fun boundsForAge(age: Int): Bounds =
            when {
                age < 40 -> Bounds(deepMaxFraction = 0.28f, remMaxFraction = 0.28f)
                age < 65 -> Bounds(deepMaxFraction = 0.25f, remMaxFraction = 0.26f)
                else -> Bounds(deepMaxFraction = 0.22f, remMaxFraction = 0.24f)
            }

        /**
         * Empirical (deep%, rem%) additive corrections to subtract from the wearable's
         * raw reading before comparing against [boundsForAge]. Values >0 mean the
         * device over-reports that stage; we shrink it back toward expected ranges.
         *
         * - Fitbit: known REM over-estimation vs PSG (~+3% absolute) — de Zambotti 2019, Chinoy 2021.
         * - Apple Watch: known deep under-estimation (~-2%) — Chinoy 2021.
         * - Oura: most accurate among consumer wearables; treated as ground truth (no correction).
         * - Garmin: small REM over-estimation reported but inconsistent across studies; no correction
         *   until additional data lands.
         */
        fun correctionFor(deviceSource: String?): StageCorrection {
            val key = deviceSource?.lowercase()?.trim().orEmpty()
            return when {
                key.contains("fitbit") -> StageCorrection(deepAdjust = 0f, remAdjust = -0.03f)
                key.contains("apple") -> StageCorrection(deepAdjust = +0.02f, remAdjust = 0f)
                key.contains("oura") -> StageCorrection.NONE
                key.contains("garmin") -> StageCorrection.NONE
                else -> StageCorrection.NONE
            }
        }

        /** True if (deep%, rem%) is within the device-adjusted bounds for [age]. */
        fun isValidArchitecture(
            deepFraction: Float,
            remFraction: Float,
            age: Int,
            deviceSource: String?,
        ): Boolean {
            val bounds = boundsForAge(age)
            val (adjDeep, adjRem) = adjusted(deepFraction, remFraction, deviceSource)
            return adjDeep <= bounds.deepMaxFraction && adjRem <= bounds.remMaxFraction
        }

        /**
         * True if either deep% or rem% lies within the suspicious band — i.e.
         * above [Bounds.suspiciousFractionOfCeiling] (default 85%) of its
         * respective ceiling but still below the ceiling itself. Such nights
         * are still scored, with architecture weight reduced.
         */
        fun isSuspiciousArchitecture(
            deepFraction: Float,
            remFraction: Float,
            age: Int,
            deviceSource: String?,
        ): Boolean {
            val bounds = boundsForAge(age)
            val (adjDeep, adjRem) = adjusted(deepFraction, remFraction, deviceSource)
            if (!(adjDeep <= bounds.deepMaxFraction && adjRem <= bounds.remMaxFraction)) {
                return false // Already invalid.
            }
            val deepWarn = bounds.deepMaxFraction * bounds.suspiciousFractionOfCeiling
            val remWarn = bounds.remMaxFraction * bounds.suspiciousFractionOfCeiling
            return adjDeep >= deepWarn || adjRem >= remWarn
        }

        /**
         * Compose a one-line human-readable explanation when the night is invalid
         * or suspicious. Returns null when both checks pass.
         */
        fun getValidationWarning(
            deepFraction: Float,
            remFraction: Float,
            age: Int,
            deviceSource: String?,
        ): String? {
            val bounds = boundsForAge(age)
            val (adjDeep, adjRem) = adjusted(deepFraction, remFraction, deviceSource)
            val deepPct = (adjDeep * 100f).format1()
            val remPct = (adjRem * 100f).format1()
            val deepWarn = bounds.deepMaxFraction * bounds.suspiciousFractionOfCeiling
            val remWarn = bounds.remMaxFraction * bounds.suspiciousFractionOfCeiling
            return when {
                adjDeep > bounds.deepMaxFraction ->
                    "Deep ${deepPct}% above age band ${bounds.deepMaxFraction.toPct()}%"
                adjRem > bounds.remMaxFraction ->
                    "REM ${remPct}% above age band ${bounds.remMaxFraction.toPct()}%"
                adjDeep >= deepWarn ->
                    "Deep ${deepPct}% in suspicious band (>=${deepWarn.toPct()}%)"
                adjRem >= remWarn ->
                    "REM ${remPct}% in suspicious band (>=${remWarn.toPct()}%)"
                else -> null
            }
        }

        /** One-shot evaluation: validate + suspicious + warning. */
        fun evaluate(
            deepFraction: Float,
            remFraction: Float,
            age: Int,
            deviceSource: String?,
        ): Result {
            val valid = isValidArchitecture(deepFraction, remFraction, age, deviceSource)
            val suspicious =
                valid &&
                    isSuspiciousArchitecture(deepFraction, remFraction, age, deviceSource)
            return Result(
                valid = valid,
                suspicious = suspicious,
                warning = getValidationWarning(deepFraction, remFraction, age, deviceSource),
            )
        }

        private fun adjusted(
            deep: Float,
            rem: Float,
            deviceSource: String?,
        ): Pair<Float, Float> {
            val c = correctionFor(deviceSource)
            return Pair(
                (deep + c.deepAdjust).coerceAtLeast(0f),
                (rem + c.remAdjust).coerceAtLeast(0f),
            )
        }

        private fun Float.toPct(): String = (this * 100f).format1()

        private fun Float.format1(): String = "%.1f".format(this)

        /**
         * Stage-level correction subtracted from the wearable's reading before
         * comparing against age-banded bounds.
         */
        data class StageCorrection(
            val deepAdjust: Float,
            val remAdjust: Float,
        ) {
            companion object {
                val NONE = StageCorrection(deepAdjust = 0f, remAdjust = 0f)
            }
        }
    }
