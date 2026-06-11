package app.readylytics.health.ui.heartrate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.repository.SelectedDateRepository
import app.readylytics.health.domain.display.MetricFormatter
import app.readylytics.health.domain.heartrate.HrZoneClassifier
import app.readylytics.health.domain.repository.HeartRateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class HeartRateDetailViewModel
    @Inject
    constructor(
        private val heartRateRepository: HeartRateRepository,
        private val settingsRepository: SettingsRepository,
        private val selectedDateRepository: SelectedDateRepository,
    ) : ViewModel() {
        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<HeartRateDetailUiState> =
            combine(
                selectedDateRepository.selectedDate,
                settingsRepository.userPreferences,
            ) { date, prefs -> date to prefs }
                .flatMapLatest { (date, prefs) ->
                    val zoneId = ZoneId.systemDefault()
                    val startMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
                    val endMs =
                        date
                            .plusDays(1)
                            .atStartOfDay(zoneId)
                            .toInstant()
                            .toEpochMilli()

                    heartRateRepository.observeByTimeRange(startMs, endMs).map { entities ->
                        if (entities.isEmpty()) {
                            return@map HeartRateDetailUiState(
                                selectedDate = date,
                                isLoading = false,
                                zone1MinBpm = prefs.zone1MinBpm,
                                zone1MaxBpm = prefs.zone1MaxBpm,
                                zone2MaxBpm = prefs.zone2MaxBpm,
                                zone3MaxBpm = prefs.zone3MaxBpm,
                                zone4MaxBpm = prefs.zone4MaxBpm,
                            )
                        }

                        val samples =
                            entities.map { entity ->
                                HrSample(
                                    timeMs = entity.timestampMs,
                                    bpm = entity.beatsPerMinute,
                                    zone = HrZoneClassifier.classify(entity.beatsPerMinute, prefs),
                                )
                            }

                        val bpms = samples.map { it.bpm }
                        val zoneTotals = computeZoneTotals(samples)

                        HeartRateDetailUiState(
                            samples = samples,
                            minBpm = bpms.min(),
                            maxBpm = bpms.max(),
                            avgBpm = bpms.sum() / bpms.size,
                            zoneTotals = zoneTotals,
                            selectedDate = date,
                            isLoading = false,
                            zone1MinBpm = prefs.zone1MinBpm,
                            zone1MaxBpm = prefs.zone1MaxBpm,
                            zone2MaxBpm = prefs.zone2MaxBpm,
                            zone3MaxBpm = prefs.zone3MaxBpm,
                            zone4MaxBpm = prefs.zone4MaxBpm,
                        )
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = HeartRateDetailUiState(),
                )

        fun onPreviousDay() {
            viewModelScope.launch { selectedDateRepository.selectPreviousDay() }
        }

        fun onNextDay() {
            viewModelScope.launch { selectedDateRepository.selectNextDay() }
        }
    }

private fun computeZoneTotals(samples: List<HrSample>): Map<Int, ZoneTotal> {
    if (samples.size < 2) return emptyMap()

    val durationByZone = mutableMapOf<Int, Long>()
    for (i in 0 until samples.size - 1) {
        val segmentMs = samples[i + 1].timeMs - samples[i].timeMs
        if (segmentMs in 0L..10 * 60 * 1000L) {
            durationByZone[samples[i].zone] =
                (durationByZone[samples[i].zone] ?: 0L) + segmentMs
        }
    }

    val totalMs = durationByZone.values.sum().takeIf { it > 0 } ?: return emptyMap()

    return durationByZone.mapValues { (_, ms) ->
        val fraction = ms.toFloat() / totalMs.toFloat()
        ZoneTotal(
            durationMs = ms,
            percent = fraction,
            formattedPercent = MetricFormatter.formatZonePercent(fraction),
        )
    }
}
