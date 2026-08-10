package app.readylytics.health.domain.airecommendation

import app.readylytics.health.domain.model.RecoveryFlag
import app.readylytics.health.domain.repository.WorkoutData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DailyPromptFormatterTest {
    @Test
    fun `format includes all populated sections and repeated blocks`() {
        val text = DailyPromptFormatter.format(populatedPromptData())

        listOf("## A.", "## B.", "## C.", "## D.", "## E.", "## F.", "## G.", "## H.").forEach { header ->
            assertTrue("Missing $header", text.contains(header))
        }
        assertTrue(text.contains("Run"))
        assertTrue(text.contains("OVERREACHING"))
        assertTrue(text.contains("Monday, Wednesday"))
        assertTrue(text.contains("78"))
        assertTrue(text.contains("435"))
    }

    @Test
    fun `format renders explicit unavailable values rather than unresolved tokens`() {
        val text = DailyPromptFormatter.format(emptyPromptData())

        assertTrue(text.contains("insufficient data"))
        assertFalse(text.contains("{{"))
        assertFalse(text.contains("}}"))
        assertFalse(text.contains("#each"))
        assertFalse(text.contains("/each"))
    }

    @Test
    fun `format renders calibration and missing flags without numeric fabrication`() {
        val text = DailyPromptFormatter.format(calibratingPromptData())

        assertTrue(text.contains("CALIBRATING"))
        assertTrue(text.contains("Calibration"))
        assertTrue(text.contains("insufficient data"))
    }

    @Test
    fun `format renders no-workout and no-active-flag cases`() {
        val text = DailyPromptFormatter.format(emptyPromptData())

        assertTrue(text.contains("no workouts yesterday"))
        assertTrue(text.contains("no active recovery flags"))
    }

    private fun populatedPromptData(): DailyPromptData =
        DailyPromptData(
            date = LocalDate.of(2026, 8, 9),
            physiologyProfile = "Active",
            calibrationPhase = "Mature",
            baselineObservationCount = 60,
            isCalibrating = false,
            activeTrainingLoadSource = "Workout only",
            everydayLoadConfidence = "High",
            today =
                TodayPromptData(
                    readinessScore = 78f,
                    restorationScore = 0.82f,
                    hrvBaseline = 45,
                    hrvMuMssd = 44.2f,
                    hrvSigmaMssd = 0.31f,
                    restingHeartRate = 52,
                    restingHrRatio = 1.02f,
                    rhrSigma = 0.9f,
                    nocturnalHrv = 46,
                    zLnHrv = 0.1f,
                    zRhr = 0.2f,
                    baselineCalculatedAtDate = LocalDate.of(2026, 7, 15),
                ),
            yesterdaySleep =
                YesterdaySleepPromptData(
                    sleepScore = 81f,
                    sleepDurationMinutes = 435,
                    deepSleepPercent = 18f,
                    remSleepPercent = 22f,
                    supplementalSleepDurationMinutes = 25,
                    napCount = 1,
                    avgSleepingSpo2 = 96f,
                ),
            yesterdayWorkouts =
                listOf(
                    YesterdayWorkout(
                        workout = workoutData(),
                        modelTrimp = 130f,
                        roundedGainedStrain = "0.4",
                        preciseGainedStrain = "0.38",
                        loadClassification = "MODERATE",
                        intensity = "MODERATE",
                    ),
                ),
            loadState =
                LoadStatePromptData(
                    acuteLoad = 85f,
                    chronicLoad = 80f,
                    strainRatio = 1.06f,
                    loadScore = 88f,
                    totalRasWorkoutOnly = 350f,
                    totalRasEverydayHr = 410f,
                    everydayCoverageMinutes = 120,
                ),
            activeRecoveryFlags =
                listOf(
                    RecoveryFlagPrompt(
                        flagName = RecoveryFlag.OVERREACHING,
                        plainEnglishGloss = RecoveryFlagGlossary.explain(RecoveryFlag.OVERREACHING),
                    ),
                ),
            workoutPattern =
                WorkoutPatternSummary(
                    lookbackMonths = 3,
                    totalWorkoutsInWindow = 24,
                    exerciseTypeBreakdown =
                        listOf(
                            ExerciseTypePattern(
                                exerciseType = "Run",
                                frequencyPerWeek = 0.5f,
                                averageTrimp = 120f,
                                averageDurationMinutes = 45f,
                                averageLoadClassification = null,
                                preferredDaysOfWeek = listOf("Monday", "Wednesday"),
                            ),
                        ),
                    restDaysPerWeekAverage = 4.5f,
                    mostRecentRestDayGapDays = 1,
                    currentConsecutiveTrainingDayStreak = 3,
                ),
        )

    private fun emptyPromptData(): DailyPromptData =
        DailyPromptData(
            date = LocalDate.of(2026, 8, 9),
            physiologyProfile = null,
            calibrationPhase = null,
            baselineObservationCount = null,
            isCalibrating = true,
            activeTrainingLoadSource = "Workout only",
            everydayLoadConfidence = null,
            today =
                TodayPromptData(
                    readinessScore = null,
                    restorationScore = null,
                    hrvBaseline = null,
                    hrvMuMssd = null,
                    hrvSigmaMssd = null,
                    restingHeartRate = null,
                    restingHrRatio = null,
                    rhrSigma = null,
                    nocturnalHrv = null,
                    zLnHrv = null,
                    zRhr = null,
                    baselineCalculatedAtDate = null,
                ),
            yesterdaySleep = null,
            yesterdayWorkouts = emptyList(),
            loadState =
                LoadStatePromptData(
                    acuteLoad = null,
                    chronicLoad = null,
                    strainRatio = null,
                    loadScore = null,
                    totalRasWorkoutOnly = null,
                    totalRasEverydayHr = null,
                    everydayCoverageMinutes = null,
                ),
            activeRecoveryFlags = emptyList(),
            workoutPattern =
                WorkoutPatternSummary(
                    lookbackMonths = 3,
                    totalWorkoutsInWindow = 0,
                    exerciseTypeBreakdown = emptyList(),
                    restDaysPerWeekAverage = 7f,
                    mostRecentRestDayGapDays = 0,
                    currentConsecutiveTrainingDayStreak = 0,
                ),
        )

    private fun calibratingPromptData(): DailyPromptData =
        emptyPromptData().copy(
            calibrationPhase = "Calibration",
            baselineObservationCount = 3,
            activeRecoveryFlags =
                listOf(
                    RecoveryFlagPrompt(
                        flagName = RecoveryFlag.CALIBRATING,
                        plainEnglishGloss = RecoveryFlagGlossary.explain(RecoveryFlag.CALIBRATING),
                    ),
                ),
        )

    private fun workoutData(): WorkoutData =
        WorkoutData(
            id = "w1",
            startTime = LocalDate.of(2026, 8, 8).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
            endTime = LocalDate.of(2026, 8, 8).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() + 3_600_000L,
            exerciseType = "Run",
            durationMinutes = 45,
            zone1Minutes = 10f,
            zone2Minutes = 15f,
            zone3Minutes = 10f,
            zone4Minutes = 5f,
            zone5Minutes = 0f,
            trimp = 120f,
            avgHr = 150f,
            deviceName = "Watch",
        )
}
