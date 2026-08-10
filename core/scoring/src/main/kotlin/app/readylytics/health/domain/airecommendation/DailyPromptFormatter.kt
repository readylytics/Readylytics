package app.readylytics.health.domain.airecommendation

import app.readylytics.health.domain.repository.WorkoutData
import java.util.Locale

/**
 * Pure stable-English formatter mirroring the section structure of
 * `internal-docs/ai-recommendations/DAILY_PROMPT_TEMPLATE.md`. It has no Android or resource
 * dependency and never emits unresolved `{{placeholder}}` tokens: unavailable values render as
 * "insufficient data" and empty lists render as explicit markers.
 */
object DailyPromptFormatter {
    fun format(data: DailyPromptData): String {
        val sb = StringBuilder()
        sb.appendLine("Today's data for ${data.date} — generate a training recommendation per the Output Contract defined in the system prompt.")
        sb.appendLine()
        sb.appendSectionA(data)
        sb.appendSectionB(data)
        sb.appendSectionC(data)
        sb.appendSectionD(data)
        sb.appendSectionE(data)
        sb.appendSectionF(data)
        sb.appendSectionG(data)
        sb.appendLine("## H. Task")
        sb.appendLine(
            "Using the data above, produce today's recommendation in the exact structure defined in the " +
                "system prompt's Output Contract. Cite the specific fields above (by the values given) " +
                "that drove your rationale.",
        )
        return sb.toString()
    }

    private fun StringBuilder.appendSectionA(data: DailyPromptData) {
        appendLine("## A. Profile & Calibration Context")
        appendLine("- Physiology profile: ${orUnavailable(data.physiologyProfile)}")
        appendLine("- Calibration phase: ${orUnavailable(data.calibrationPhase)}")
        appendLine("- Baseline observation count: ${intOrUnavailable(data.baselineObservationCount)} valid days")
        appendLine("- Is calibrating: ${data.isCalibrating}")
        appendLine("- Active Training Load source (drives Readiness): ${data.activeTrainingLoadSource}")
        appendLine(
            "- Everyday-load confidence (only meaningful if the source above is Everyday heart-rate load): " +
                "${orUnavailable(data.everydayLoadConfidence)}",
        )
        appendLine("- Advisor data confidence: ${orUnavailable(data.advisorDataConfidence)}")
        appendLine()
    }

    private fun StringBuilder.appendSectionB(data: DailyPromptData) {
        val today = data.today
        appendLine("## B. Today's Readiness & Baselines")
        appendLine("- Readiness score (from the active source above): ${numberOrUnavailable(today.readinessScore)}")
        appendLine("- Readiness band: ${orUnavailable(today.readinessBand)}")
        appendLine("- Permitted recommendation ceiling: ${today.permittedRecommendation.name}")
        appendLine("- Restoration sub-score (sRest): ${numberOrUnavailable(today.restorationScore)}")
        appendLine(
            "- HRV baseline: ${intOrUnavailable(today.hrvBaseline)} ms — mu ${numberOrUnavailable(today.hrvMuMssd)}, " +
                "sigma ${numberOrUnavailable(today.hrvSigmaMssd)}",
        )
        appendLine(
            "- Resting heart rate today: ${intOrUnavailable(today.restingHeartRate)} bpm — vs. baseline ratio " +
                "${numberOrUnavailable(today.restingHrRatio)}, baseline sigma ${numberOrUnavailable(today.rhrSigma)}",
        )
        appendLine("- Last night's nocturnal HRV: ${intOrUnavailable(today.nocturnalHrv)}")
        appendLine(
            "- z-scores vs. this person's own baseline (standard deviations from personal norm): " +
                "HRV ${numberOrUnavailable(today.zLnHrv)}, RHR ${numberOrUnavailable(today.zRhr)}",
        )
        appendLine("- Baseline last (re)calculated: ${dateOrUnavailable(today.baselineCalculatedAtDate)}")
        appendLine("- Today completed workouts: ${today.todayCompletedWorkouts}")
        appendLine("- Today TRIMP: ${numberOrUnavailable(today.todayTrimp)}")
        appendLine("- Today training minutes: ${intOrUnavailable(today.todayTrainingMinutes)}")
        appendLine("- Data current until: ${orUnavailable(today.dataCurrentUntil)}")
        appendLine()
    }

    private fun StringBuilder.appendSectionC(data: DailyPromptData) {
        val sleep = data.yesterdaySleep
        appendLine("## C. Yesterday's Sleep Score Breakdown")
        if (sleep == null) {
            appendLine("- Sleep data: insufficient data")
            appendLine()
            return
        }
        appendLine("- Sleep Score: ${numberOrUnavailable(sleep.sleepScore)}")
        appendLine("- Sleep duration: ${intOrUnavailable(sleep.sleepDurationMinutes)} minutes")
        appendLine(
            "- Deep sleep: ${numberOrUnavailable(sleep.deepSleepPercent)}% — REM sleep: " +
                "${numberOrUnavailable(sleep.remSleepPercent)}%",
        )
        appendLine(
            "- Supplemental sleep (naps): ${intOrUnavailable(sleep.supplementalSleepDurationMinutes)} minutes across " +
                "${intOrUnavailable(sleep.napCount)} nap(s)",
        )
        appendLine("- Average sleeping SpO2: ${numberOrUnavailable(sleep.avgSleepingSpo2)}")
        appendLine()
    }

    private fun StringBuilder.appendSectionD(data: DailyPromptData) {
        appendLine("## D. Yesterday's Workout(s)")
        if (data.yesterdayWorkouts.isEmpty()) {
            appendLine("- no workouts yesterday: true")
            appendLine()
            return
        }
        data.yesterdayWorkouts.forEach { workoutBlock ->
            val workout = workoutBlock.workout
            appendLine(
                "- Type: ${workout.exerciseType}, duration ${workout.durationMinutes} min, avg HR " +
                    "${workout.avgHr.toInt()} bpm",
            )
            appendLine(
                "- TRIMP: ${number(workout.trimp)} (model TRIMP ${numberOrUnavailable(workoutBlock.modelTrimp)} when present)",
            )
            appendLine(
                "- HR zone breakdown (minutes): zone 1 ${zoneMinutes(workout.zone1Minutes)}, " +
                    "zone 2 ${zoneMinutes(workout.zone2Minutes)}, zone 3 ${zoneMinutes(workout.zone3Minutes)}, " +
                    "zone 4 ${zoneMinutes(workout.zone4Minutes)}, zone 5 ${zoneMinutes(workout.zone5Minutes)}",
            )
            appendLine(
                "- Gained strain from this session: ${orUnavailable(workoutBlock.roundedGainedStrain)} " +
                    "(precise: ${orUnavailable(workoutBlock.preciseGainedStrain)}) — how much this workout " +
                    "raised the Strain Ratio",
            )
            appendLine(
                "- Load classification: ${orUnavailable(workoutBlock.loadClassification)}, " +
                    "intensity: ${orUnavailable(workoutBlock.intensity)}",
            )
        }
        appendLine()
    }

    private fun StringBuilder.appendSectionE(data: DailyPromptData) {
        val load = data.loadState
        appendLine("## E. Current Training Load State")
        appendLine("- Acute Load (ATL, 7-day EMA of TRIMP): ${numberOrUnavailable(load.acuteLoad)}")
        appendLine("- Chronic Load (CTL, 42-day EMA of TRIMP): ${numberOrUnavailable(load.chronicLoad)}")
        appendLine(
            "- Strain Ratio (ATL ÷ CTL — internal term, describe qualitatively): " +
                "${numberOrUnavailable(load.strainRatio)}",
        )
        appendLine("- Load Score (0–100): ${numberOrUnavailable(load.loadScore)}")
        appendLine(
            "- 7-day RAS total (informational only — never drives Readiness): " +
                "Workout only ${numberOrUnavailable(load.totalRasWorkoutOnly)}, " +
                "Everyday HR ${numberOrUnavailable(load.totalRasEverydayHr)}",
        )
        appendLine(
            "- Everyday-load coverage (only relevant if that source is active): " +
                "${intOrUnavailable(load.everydayCoverageMinutes)} minutes",
        )
        appendLine("- Load context: ${orUnavailable(load.loadContext)}")
        appendLine()
    }

    private fun StringBuilder.appendSectionF(data: DailyPromptData) {
        appendLine("## F. Active Recovery Flags")
        if (data.activeRecoveryFlags.isEmpty()) {
            appendLine("- no active recovery flags: true")
            appendLine()
            return
        }
        data.activeRecoveryFlags.forEach { flag ->
            appendLine("- ${flag.flagName.name} — ${flag.plainEnglishGloss}")
        }
        appendLine()
    }

    private fun StringBuilder.appendSectionG(data: DailyPromptData) {
        val pattern = data.workoutPattern
        appendLine("## G. Typical Workout Pattern (last ${pattern.lookbackMonths} months)")
        appendLine("- Total workouts in window: ${pattern.totalWorkoutsInWindow}")
        pattern.exerciseTypeBreakdown.forEach { typePattern ->
            appendLine(
                "- ${typePattern.exerciseType}: ${number(typePattern.frequencyPerWeek)}/week, " +
                    "avg TRIMP ${numberOrUnavailable(typePattern.averageTrimp)}, " +
                    "avg duration ${numberOrUnavailable(typePattern.averageDurationMinutes)} min, " +
                    "usual load ${orUnavailable(typePattern.averageLoadClassification)}, " +
                    "typically on ${typePattern.preferredDaysOfWeek.joinToString(", ")}",
            )
        }
        appendLine("- Average rest days per week: ${number(pattern.restDaysPerWeekAverage)}")
        appendLine("- Days since last rest day: ${pattern.mostRecentRestDayGapDays}")
        appendLine("- Current consecutive training-day streak: ${pattern.currentConsecutiveTrainingDayStreak}")
        appendLine()
    }

    private fun orUnavailable(value: String?): String = value ?: INSUFFICIENT_DATA

    private fun intOrUnavailable(value: Int?): String = value?.toString() ?: INSUFFICIENT_DATA

    private fun dateOrUnavailable(value: java.time.LocalDate?): String = value?.toString() ?: INSUFFICIENT_DATA

    private fun numberOrUnavailable(value: Float?): String =
        value?.let(::number) ?: INSUFFICIENT_DATA

    private fun zoneMinutes(value: Float): String = value.toInt().toString()

    private fun number(value: Float): String {
        if (!value.isFinite()) return INSUFFICIENT_DATA
        val formatted = String.format(Locale.ROOT, "%.1f", value)
        return if (formatted.contains('.')) formatted.trimEnd('0').trimEnd('.') else formatted
    }

    private const val INSUFFICIENT_DATA = "insufficient data"
}
