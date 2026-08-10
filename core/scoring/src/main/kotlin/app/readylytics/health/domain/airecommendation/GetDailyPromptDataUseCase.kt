package app.readylytics.health.domain.airecommendation

import app.readylytics.health.domain.model.CalibrationPhase
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.LoadSourceSelector
import app.readylytics.health.domain.model.RecoveryFlag
import app.readylytics.health.domain.model.resolveAdvisorConfidence
import app.readylytics.health.domain.model.scoreStatus
import app.readylytics.health.domain.model.toLoadContext
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.preferences.scoringZone
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.WorkoutRepository
import app.readylytics.health.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.domain.scoring.LoadCoverageConfidence
import app.readylytics.health.domain.scoring.LoadSourceMode
import app.readylytics.health.domain.scoring.ScoringConstants
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

/**
 * Orchestrates persisted data into [DailyPromptData] for the manual AI recommendation workflow.
 *
 * Reads persisted `DailySummary` rows (never recomputes scores), bounded workout ranges, the
 * user's configured Training Load source, and per-workout display metrics. The active source
 * ([LoadSourceMode]) selects which ATL/CTL/ratio/load/readiness variant is reported; RAS totals
 * are informational only.
 */
class GetDailyPromptDataUseCase
    @Inject
    constructor(
        private val dailySummaryRepository: DailySummaryRepository,
        private val workoutRepository: WorkoutRepository,
        private val preferencesReader: UserPreferencesReader,
        private val getWorkoutDisplayMetricsUseCase: GetWorkoutDisplayMetricsUseCase,
        private val patternSummaryUseCase: ComputeWorkoutPatternSummaryUseCase,
    ) {
        suspend fun execute(today: LocalDate): DailyPromptData {
            val preferences = preferencesReader.userPreferences.first()
            val zoneId = preferences.scoringZone()
            val todayMidnight = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val yesterday = today.minusDays(1)
            val yesterdayMidnight = yesterday.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val tomorrowMidnight = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val lookbackStart =
                today.minusMonths(ScoringConstants.AiRecommendation.LOOKBACK_MONTHS.toLong())
            val lookbackStartMidnight =
                lookbackStart.atStartOfDay(zoneId).toInstant().toEpochMilli()

            val todaySummary = dailySummaryRepository.getByDate(todayMidnight)
            val yesterdaySummary = dailySummaryRepository.getByDate(yesterdayMidnight)

            val yesterdayWorkouts =
                workoutRepository.getInRange(yesterdayMidnight, todayMidnight)
            val patternWorkouts =
                workoutRepository.getInRange(lookbackStartMidnight, tomorrowMidnight)

            val historicalStart =
                yesterday
                    .minusDays(ScoringConstants.CHRONIC_DAYS)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val historicalSummaries = dailySummaryRepository.getSince(historicalStart)

            val sourceMode = preferences.strainLoadSourceMode

            val todaysWorkouts = workoutRepository.getInRange(todayMidnight, tomorrowMidnight)
            val todayCompletedWorkouts = todaysWorkouts.size
            val todayTrimp = todaysWorkouts.mapNotNull { it.trimp }.sum().takeIf { todaysWorkouts.isNotEmpty() }
            val todayTrainingMinutes = todaysWorkouts.sumOf { it.durationMinutes }.takeIf { todaysWorkouts.isNotEmpty() }
            val dataCurrentUntil =
                if (todaysWorkouts.isNotEmpty()) {
                    Instant.ofEpochMilli(todaysWorkouts.maxOf { it.endTime }).toString()
                } else {
                    Instant.now().toString()
                }

            val isEverydaySourceLowConfidence =
                sourceMode == LoadSourceMode.EVERYDAY_HEART_RATE &&
                    todaySummary?.everydayLoadConfidence == LoadCoverageConfidence.LOW.name
            val hasMajorMissingSignals =
                todaySummary?.recoveryFlags?.any {
                    it == RecoveryFlag.HRV_MISSING || it == RecoveryFlag.STAGES_MISSING
                } ?: false
            val phase =
                todaySummary?.snapshotCalibrationPhase?.let { enumValueOf<CalibrationPhase>(it.uppercase()) }
                    ?: CalibrationPhase.CALIBRATION
            val advisorConf =
                resolveAdvisorConfidence(phase, hasMajorMissingSignals, isEverydaySourceLowConfidence).name

            val workoutBlocks =
                yesterdayWorkouts.map { workout ->
                    val metrics =
                        getWorkoutDisplayMetricsUseCase.execute(
                            workout = workout,
                            preferences = preferences,
                            historicalSummaries = historicalSummaries,
                        )
                    YesterdayWorkout(
                        workout = workout,
                        modelTrimp = metrics.preciseTrimp,
                        roundedGainedStrain = metrics.gainedStrainDisplay,
                        preciseGainedStrain = metrics.gainedStrain.toString(),
                        loadClassification = metrics.classification?.finalLoad?.name,
                        intensity = metrics.classification?.intensity?.name,
                    )
                }

            return DailyPromptData(
                date = today,
                physiologyProfile =
                    todaySummary?.snapshotProfile ?: preferences.physiologyProfile.name,
                calibrationPhase = todaySummary?.snapshotCalibrationPhase,
                baselineObservationCount = todaySummary?.baselineObservationCount,
                isCalibrating = todaySummary?.isCalibrating ?: false,
                activeTrainingLoadSource = sourceName(sourceMode),
                everydayLoadConfidence = todaySummary?.everydayLoadConfidence,
                advisorDataConfidence = advisorConf,
                today =
                    todaySummary?.let { mapToday(it, sourceMode, todayCompletedWorkouts, todayTrimp, todayTrainingMinutes, dataCurrentUntil) }
                        ?: TodayPromptData(
                            readinessScore = null,
                            readinessBand = null,
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
                            todayCompletedWorkouts = todayCompletedWorkouts,
                            todayTrimp = todayTrimp,
                            todayTrainingMinutes = todayTrainingMinutes,
                            dataCurrentUntil = dataCurrentUntil,
                        ),
                yesterdaySleep = yesterdaySummary?.let(::mapYesterdaySleep),
                yesterdayWorkouts = workoutBlocks,
                loadState =
                    todaySummary?.let { mapLoadState(it, sourceMode) }
                        ?: LoadStatePromptData(
                            acuteLoad = null,
                            chronicLoad = null,
                            strainRatio = null,
                            loadScore = null,
                            loadContext = null,
                            totalRasWorkoutOnly = null,
                            totalRasEverydayHr = null,
                            everydayCoverageMinutes = null,
                        ),
                activeRecoveryFlags =
                    todaySummary?.recoveryFlags
                        ?.map { flag ->
                            RecoveryFlagPrompt(
                                flagName = flag,
                                plainEnglishGloss = RecoveryFlagGlossary.explain(flag),
                            )
                        }?.sortedBy { it.flagName.name }
                        ?: emptyList(),
                workoutPattern = patternSummaryUseCase.execute(patternWorkouts, today),
            )
        }

        private fun mapToday(
            summary: DailySummary,
            mode: LoadSourceMode,
            todayCompletedWorkouts: Int,
            todayTrimp: Float?,
            todayTrainingMinutes: Int?,
            dataCurrentUntil: String?
        ): TodayPromptData =
            TodayPromptData(
                readinessScore = LoadSourceSelector.selectReadiness(summary, mode),
                readinessBand = LoadSourceSelector.selectReadiness(summary, mode)?.scoreStatus()?.name,
                restorationScore = summary.sRest,
                hrvBaseline = summary.hrvBaseline,
                hrvMuMssd = summary.hrvMuMssd,
                hrvSigmaMssd = summary.hrvSigmaMssd,
                restingHeartRate = summary.restingHeartRate,
                restingHrRatio = summary.restingHrRatio,
                rhrSigma = summary.rhrSigma,
                nocturnalHrv = summary.nocturnalHrv,
                zLnHrv = summary.zLnHrv,
                zRhr = summary.zRhr,
                baselineCalculatedAtDate = summary.baselineCalculatedAtDate,
                todayCompletedWorkouts = todayCompletedWorkouts,
                todayTrimp = todayTrimp,
                todayTrainingMinutes = todayTrainingMinutes,
                dataCurrentUntil = dataCurrentUntil,
            )

        private fun mapYesterdaySleep(summary: DailySummary): YesterdaySleepPromptData =
            YesterdaySleepPromptData(
                sleepScore = summary.sleepScore,
                sleepDurationMinutes = summary.sleepDurationMinutes,
                deepSleepPercent = summary.deepSleepPercent,
                remSleepPercent = summary.remSleepPercent,
                supplementalSleepDurationMinutes = summary.supplementalSleepDurationMinutes,
                napCount = summary.napCount,
                avgSleepingSpo2 = summary.avgSleepingSpo2,
            )

        private fun mapLoadState(summary: DailySummary, mode: LoadSourceMode): LoadStatePromptData =
            LoadStatePromptData(
                acuteLoad = LoadSourceSelector.selectAtl(summary, mode),
                chronicLoad = LoadSourceSelector.selectCtl(summary, mode),
                strainRatio = LoadSourceSelector.selectStrainRatio(summary, mode),
                loadScore = LoadSourceSelector.selectLoadScore(summary, mode),
                loadContext = LoadSourceSelector.selectStrainRatio(summary, mode)?.toLoadContext()?.name,
                totalRasWorkoutOnly = summary.totalRasWorkoutOnly,
                totalRasEverydayHr = summary.totalRasEverydayHr,
                everydayCoverageMinutes = summary.everydayCoverageMinutes,
            )

        private fun sourceName(mode: LoadSourceMode): String =
            when (mode) {
                LoadSourceMode.WORKOUT_ONLY -> "Workout only"
                LoadSourceMode.EVERYDAY_HEART_RATE -> "Everyday heart-rate load"
            }
    }
