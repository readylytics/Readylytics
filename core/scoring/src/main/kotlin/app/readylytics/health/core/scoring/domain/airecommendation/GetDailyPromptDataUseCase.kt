package app.readylytics.health.core.scoring.domain.airecommendation

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.LoadSourceSelector
import app.readylytics.health.core.model.domain.model.PermittedRecommendationMapper
import app.readylytics.health.core.model.domain.model.RecoveryFlag
import app.readylytics.health.core.model.domain.model.scoreStatus
import app.readylytics.health.core.model.domain.model.toLoadContext
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.preferences.scoringZone
import app.readylytics.health.core.model.domain.repository.DailySummaryRepository
import app.readylytics.health.core.model.domain.repository.WorkoutRepository
import app.readylytics.health.core.scoring.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.core.model.domain.scoring.LoadCoverageConfidence
import app.readylytics.health.core.model.domain.scoring.LoadSourceMode
import app.readylytics.health.core.model.domain.model.PermittedRecommendation
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.scoring.domain.scoring.components.Phase
import app.readylytics.health.core.model.domain.util.toMidnightEpochMilli
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
        private val recommendedLoadCalculator: RecommendedLoadCalculator,
    ) {
        suspend fun execute(today: LocalDate): DailyPromptData = coroutineScope {
            val preferences = preferencesReader.userPreferences.first()
            val zoneId = preferences.scoringZone()
            val todayMidnight = today.toMidnightEpochMilli(zoneId)
            val yesterday = today.minusDays(1)
            val yesterdayMidnight = yesterday.toMidnightEpochMilli(zoneId)
            val tomorrowMidnight = today.plusDays(1).toMidnightEpochMilli(zoneId)
            val lookbackStart =
                today.minusMonths(ScoringConstants.AiRecommendation.LOOKBACK_MONTHS.toLong())
            val lookbackStartMidnight = lookbackStart.toMidnightEpochMilli(zoneId)
            val historicalStart =
                yesterday.minusDays(ScoringConstants.CHRONIC_DAYS).toMidnightEpochMilli(zoneId)
            val sourceMode = preferences.strainLoadSourceMode

            val todaySummaryDeferred = async { dailySummaryRepository.getByDate(todayMidnight) }
            val yesterdaySummaryDeferred = async { dailySummaryRepository.getByDate(yesterdayMidnight) }
            val patternWorkoutsDeferred =
                async { workoutRepository.getInRange(lookbackStartMidnight, tomorrowMidnight) }
            val historicalSummariesDeferred = async { dailySummaryRepository.getSince(historicalStart) }

            val todaySummary = todaySummaryDeferred.await()
            val yesterdaySummary = yesterdaySummaryDeferred.await()
            val patternWorkouts = patternWorkoutsDeferred.await()
            val historicalSummaries = historicalSummariesDeferred.await()

            val yesterdayWorkouts =
                patternWorkouts.filter { it.startTime in yesterdayMidnight until todayMidnight }
            val todaysWorkouts =
                patternWorkouts.filter { it.startTime in todayMidnight until tomorrowMidnight }
            val todayCompletedWorkouts = todaysWorkouts.size
            val todayTrimp = todaysWorkouts.sumOf { it.trimp.toDouble() }.toFloat()
            val todayTrainingMinutes = todaysWorkouts.sumOf { it.durationMinutes }.takeIf { todaysWorkouts.isNotEmpty() }
            val dataCurrentUntil =
                if (todaysWorkouts.isNotEmpty()) {
                    Instant.ofEpochMilli(todaysWorkouts.maxOf { it.endTime }).toString()
                } else {
                    preferences.lastSyncTimestamp
                        .takeIf { it > 0L }
                        ?.let { Instant.ofEpochMilli(it).toString() }
                }

            val everydayLoadConfidence =
                if (sourceMode == LoadSourceMode.EVERYDAY_HEART_RATE) {
                    todaySummary?.everydayLoadConfidence?.let {
                        runCatching { enumValueOf<LoadCoverageConfidence>(it.uppercase()) }.getOrNull()
                    }
                } else {
                    null
                }
            val hasMajorMissingSignals =
                todaySummary?.recoveryFlags?.any {
                    it == RecoveryFlag.HRV_MISSING || it == RecoveryFlag.STAGES_MISSING
                } ?: false
            val phase: Phase =
                todaySummary?.snapshotCalibrationPhase
                    ?.let { runCatching { enumValueOf<Phase>(it.uppercase()) }.getOrNull() }
                    ?: Phase.CALIBRATION
            val advisorConf =
                resolveAdvisorConfidence(phase, hasMajorMissingSignals, everydayLoadConfidence).name

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

            DailyPromptData(
                date = today,
                physiologyProfile =
                    todaySummary?.snapshotProfile ?: preferences.physiologyProfile.name,
                calibrationPhase = phase.displayName,
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
                    todaySummary?.let { mapLoadState(it, sourceMode, todayTrimp) }
                        ?: LoadStatePromptData(
                            acuteLoad = null,
                            chronicLoad = null,
                            strainRatio = null,
                            loadScore = null,
                            loadContext = null,
                            recommendedLoad = null,
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
                workoutPattern = patternSummaryUseCase.execute(patternWorkouts, today, zoneId),
            )
        }

        private fun mapToday(
            summary: DailySummary,
            mode: LoadSourceMode,
            todayCompletedWorkouts: Int,
            todayTrimp: Float?,
            todayTrainingMinutes: Int?,
            dataCurrentUntil: String?
        ): TodayPromptData {
            val readinessScore = LoadSourceSelector.selectReadiness(summary, mode)
            val metricStatus = readinessScore.scoreStatus()
            val permittedRecommendation = PermittedRecommendationMapper.resolve(
                status = metricStatus,
                flags = summary.recoveryFlags.toList(),
            )
            val recommendedAction = permittedRecommendation.takeIf { it != PermittedRecommendation.UNKNOWN }

            return TodayPromptData(
                readinessScore = readinessScore,
                readinessBand = metricStatus.name,
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
                permittedRecommendation = permittedRecommendation,
                recommendedAction = recommendedAction,
            )
        }

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

        private fun mapLoadState(
            summary: DailySummary,
            mode: LoadSourceMode,
            todayTrimp: Float?,
        ): LoadStatePromptData {
            val loadContext = LoadSourceSelector.selectStrainRatio(summary, mode)?.toLoadContext()
            return LoadStatePromptData(
                acuteLoad = LoadSourceSelector.selectAtl(summary, mode),
                chronicLoad = LoadSourceSelector.selectCtl(summary, mode),
                strainRatio = LoadSourceSelector.selectStrainRatio(summary, mode),
                loadScore = LoadSourceSelector.selectLoadScore(summary, mode),
                loadContext = loadContext?.name,
                recommendedLoad =
                    recommendedLoadCalculator
                        .compute(loadContext, todayTrimp)
                        ?.let(::RecommendedLoadPromptData),
                totalRasWorkoutOnly = summary.totalRasWorkoutOnly,
                totalRasEverydayHr = summary.totalRasEverydayHr,
                everydayCoverageMinutes = summary.everydayCoverageMinutes,
            )
        }

        private fun sourceName(mode: LoadSourceMode): String =
            when (mode) {
                LoadSourceMode.WORKOUT_ONLY -> "Workout only"
                LoadSourceMode.EVERYDAY_HEART_RATE -> "Everyday heart-rate load"
            }
    }
