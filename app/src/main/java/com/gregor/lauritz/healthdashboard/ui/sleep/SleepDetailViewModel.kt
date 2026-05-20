package com.gregor.lauritz.healthdashboard.ui.sleep

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.model.SleepTypicalRanges
import com.gregor.lauritz.healthdashboard.domain.repository.SleepSessionRepository
import com.gregor.lauritz.healthdashboard.domain.repository.SleepStageData
import com.gregor.lauritz.healthdashboard.domain.util.TimezoneProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

private data class PercentagesTriple(
    val deepSleepPercent: Float,
    val remSleepPercent: Float,
    val lightSleepPercent: Float,
    val awakePercent: Float,
)

@Stable
data class SleepDetailUiState(
    val session: SleepSessionEntity? = null,
    val stageTimeline: List<SleepStageData> = emptyList(),
    val deepSleepPercent: Float = 0f,
    val remSleepPercent: Float = 0f,
    val lightSleepPercent: Float = 0f,
    val awakePercent: Float = 0f,
    val sleepScore: Float? = null,
    val typicalRanges: SleepTypicalRanges = SleepTypicalRanges.DEFAULT,
    val selectedDate: LocalDate = LocalDate.now(),
)

@HiltViewModel
class SleepDetailViewModel(
    private val sleepSessionDao: SleepSessionDao,
    private val selectedDateRepository: SelectedDateRepository,
    private val sleepSessionRepository: SleepSessionRepository,
    private val timezoneProvider: TimezoneProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    /**
     * Secondary constructor used by Hilt — delegates to the primary with the default dispatcher.
     */
    @Inject
    constructor(
        sleepSessionDao: SleepSessionDao,
        selectedDateRepository: SelectedDateRepository,
        sleepSessionRepository: SleepSessionRepository,
        timezoneProvider: TimezoneProvider,
    ) : this(
        sleepSessionDao,
        selectedDateRepository,
        sleepSessionRepository,
        timezoneProvider,
        Dispatchers.Default,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<SleepDetailUiState> =
        kotlinx.coroutines.flow
            .combine(
                selectedDateRepository.selectedDate,
                timezoneProvider.timezone,
            ) { date, zoneId ->
                date to zoneId
            }.flatMapLatest { (date, zoneId) ->
                val startDateTime = date.atStartOfDay(zoneId)
                val startDayMs = startDateTime.toInstant().toEpochMilli()
                val endDayMs = startDateTime.plusDays(1).toInstant().toEpochMilli()

                sleepSessionDao
                    .observeFirstSessionEndingInRange(startDayMs, endDayMs)
                    .flatMapLatest { session ->
                        if (session == null) {
                            flowOf(SleepDetailUiState(selectedDate = date))
                        } else {
                            sleepSessionRepository
                                .observeSessionStages(session.id)
                                .map { stages -> buildDetailUiState(session, stages, date) }
                        }
                    }
            }.flowOn(ioDispatcher)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SleepDetailUiState(),
            )

    internal fun buildDetailUiState(
        session: SleepSessionEntity,
        stages: List<SleepStageData>,
        date: LocalDate,
    ): SleepDetailUiState {
        val totalMinutes =
            session.deepSleepMinutes + session.remSleepMinutes + session.lightSleepMinutes +
                session.awakeMinutes

        val percentages =
            if (totalMinutes > 0) {
                PercentagesTriple(
                    deepSleepPercent = session.deepSleepMinutes.toFloat() / totalMinutes * 100f,
                    remSleepPercent = session.remSleepMinutes.toFloat() / totalMinutes * 100f,
                    lightSleepPercent =
                        session.lightSleepMinutes.toFloat() / totalMinutes * 100f,
                    awakePercent = session.awakeMinutes.toFloat() / totalMinutes * 100f,
                )
            } else {
                PercentagesTriple(0f, 0f, 0f, 0f)
            }

        return SleepDetailUiState(
            session = session,
            stageTimeline = stages,
            deepSleepPercent = percentages.deepSleepPercent,
            remSleepPercent = percentages.remSleepPercent,
            lightSleepPercent = percentages.lightSleepPercent,
            awakePercent = percentages.awakePercent,
            sleepScore = session.sleepScore,
            typicalRanges = SleepTypicalRanges.DEFAULT,
            selectedDate = date,
        )
    }
}
