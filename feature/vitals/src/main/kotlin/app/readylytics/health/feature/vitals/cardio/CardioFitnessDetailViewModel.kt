package app.readylytics.health.feature.vitals.cardio

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.model.di.IoDispatcher
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.HealthZone
import app.readylytics.health.core.model.domain.model.ZoneBand
import app.readylytics.health.core.model.domain.preferences.Gender
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.preferences.scoringZone
import app.readylytics.health.core.model.domain.repository.DailySummaryRepository
import app.readylytics.health.core.scoring.domain.cardio.CooperCategory
import app.readylytics.health.core.scoring.domain.cardio.CooperNormsClassifier
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.common.TrendGranularity
import app.readylytics.health.core.ui.common.bucketBy
import app.readylytics.health.core.ui.common.padToRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject

@Immutable
data class CardioFitnessDetailUiState(
    val currentVo2Max: Float? = null,
    val vo2MaxDisplay: String? = null,
    val currentSource: String? = null,
    val cooperCategory: CooperCategory? = null,
    val thresholds: CooperNormsClassifier.Thresholds? = null,
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val dailyVo2Max: List<DailyDataPoint> = emptyList(),
    val averageVo2Max: Float? = null,
    val chartZoneBands: List<ZoneBand> = emptyList(),
    val rangeStartMs: Long = 0,
    val isLoading: Boolean = true,
)

private data class CardioFitnessParams(
    val range: TimeRange,
    val prefs: UserPreferences,
)

@HiltViewModel
class CardioFitnessDetailViewModel
    @Inject
    constructor(
        private val dailySummaryRepository: DailySummaryRepository,
        private val settingsRepo: UserPreferencesReader,
        private val cooperNormsClassifier: CooperNormsClassifier,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val selectedRangeFlow = MutableStateFlow(TimeRange.SEVEN_DAYS)

        @OptIn(ExperimentalCoroutinesApi::class)
        private val contentFlow =
            combine(selectedRangeFlow, settingsRepo.userPreferences, ::CardioFitnessParams)
                .distinctUntilChanged()
                .flatMapLatest { params ->
                    val zoneId = params.prefs.scoringZone()
                    val today = LocalDate.now(zoneId)
                    val startDate = today.minusDays(params.range.days.toLong() - 1)
                    val fromMs = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()

                    dailySummaryRepository.observeSince(fromMs).map { summaries ->
                        buildState(params, summaries, startDate, fromMs)
                    }
                }.flowOn(ioDispatcher)

        val uiState: StateFlow<CardioFitnessDetailUiState> =
            contentFlow.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = CardioFitnessDetailUiState(),
            )

        private fun buildState(
            params: CardioFitnessParams,
            summaries: List<DailySummary>,
            startDate: LocalDate,
            fromMs: Long,
        ): CardioFitnessDetailUiState {
            val range = params.range
            val prefs = params.prefs
            val endDate = startDate.plusDays(range.days.toLong() - 1)

            val rawPoints =
                summaries
                    .filter { it.date in startDate..endDate }
                    .mapNotNull { summary ->
                        summary.vo2Max?.let {
                            DailyDataPoint(ChronoUnit.DAYS.between(startDate, summary.date).toInt(), it)
                        }
                    }.sortedBy(DailyDataPoint::dayOffset)

            val dailyVo2Max =
                if (range.granularity == TrendGranularity.DAILY) {
                    rawPoints.padToRange(range.days)
                } else {
                    rawPoints.bucketBy(range.granularity, startDate, endDate, valueDecimalPlaces = 1)
                }

            val latest = summaries.filter { it.vo2Max != null }.maxByOrNull { it.date }
            val age = prefs.age
            val gender = prefs.gender ?: Gender.OTHER
            val category = latest?.vo2Max?.let { cooperNormsClassifier.classify(it, age, gender) }
            val thresholds = cooperNormsClassifier.thresholds(age, gender)
            val average =
                rawPoints
                    .mapNotNull { it.value }
                    .takeIf { it.isNotEmpty() }
                    ?.average()
                    ?.toFloat()

            return CardioFitnessDetailUiState(
                currentVo2Max = latest?.vo2Max,
                vo2MaxDisplay = latest?.vo2Max?.let { String.format(Locale.US, "%.1f", it) },
                currentSource = latest?.vo2MaxSource,
                cooperCategory = category,
                thresholds = thresholds,
                selectedRange = range,
                dailyVo2Max = dailyVo2Max,
                averageVo2Max = average,
                chartZoneBands = cooperZoneBands(thresholds),
                rangeStartMs = fromMs,
                isLoading = false,
            )
        }

        fun onRangeSelected(range: TimeRange) {
            selectedRangeFlow.value = range
        }

        /**
         * Collapses the 5-band Cooper norms onto the 4-band [HealthZone] scale the shared
         * [ZoneBand] chart overlay understands: SUPERIOR and EXCELLENT merge into one OPTIMAL
         * band above the "excellent" threshold, mirroring [CooperCategory.toMetricStatus].
         */
        private fun cooperZoneBands(thresholds: CooperNormsClassifier.Thresholds): List<ZoneBand> =
            listOf(
                ZoneBand(Double.NEGATIVE_INFINITY, thresholds.fair.toDouble(), HealthZone.CRITICAL),
                ZoneBand(thresholds.fair.toDouble(), thresholds.good.toDouble(), HealthZone.WARNING),
                ZoneBand(thresholds.good.toDouble(), thresholds.excellent.toDouble(), HealthZone.NEUTRAL),
                ZoneBand(thresholds.excellent.toDouble(), Double.POSITIVE_INFINITY, HealthZone.OPTIMAL),
            )
    }
