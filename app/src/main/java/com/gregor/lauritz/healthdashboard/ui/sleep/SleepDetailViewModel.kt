package com.gregor.lauritz.healthdashboard.ui.sleep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.model.SleepTypicalRanges
import com.gregor.lauritz.healthdashboard.domain.repository.SleepSessionRepository
import com.gregor.lauritz.healthdashboard.domain.repository.SleepStageData
import dagger.hilt.android.lifecycle.HiltViewModel
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
import java.time.ZoneId
import javax.inject.Inject

private data class PercentagesTriple(
    val deepSleepPercent: Float,
    val remSleepPercent: Float,
    val lightSleepPercent: Float,
    val awakePercent: Float,
)

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
class SleepDetailViewModel
    @Inject
    constructor(
        private val sleepSessionDao: SleepSessionDao,
        private val selectedDateRepository: SelectedDateRepository,
        private val sleepSessionRepository: SleepSessionRepository,
    ) : ViewModel() {
        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<SleepDetailUiState> =
            selectedDateRepository.selectedDate
                .flatMapLatest { date ->
                    val startDateTime = date.atStartOfDay(ZoneId.systemDefault())
                    val startDayMs = startDateTime.toInstant().toEpochMilli()
                    val endDayMs = startDateTime.plusDays(1).toInstant().toEpochMilli()

                    sleepSessionDao
                        .observeFirstSessionEndingInRange(startDayMs, endDayMs)
                        .flatMapLatest { session ->
                            if (session == null) {
                                kotlinx.coroutines.flow.flowOf(
                                    SleepDetailUiState(
                                        selectedDate = date,
                                    ),
                                )
                            } else {
                                sleepSessionRepository
                                    .observeSessionStages(session.id)
                                    .map { stages ->
                                        val totalMinutes =
                                            session.deepSleepMinutes + session.remSleepMinutes +
                                                session.lightSleepMinutes +
                                                session.awakeMinutes

                                        val percentages =
                                            if (totalMinutes > 0) {
                                                PercentagesTriple(
                                                    deepSleepPercent =
                                                        session.deepSleepMinutes.toFloat() / totalMinutes * 100f,
                                                    remSleepPercent =
                                                        session.remSleepMinutes.toFloat() / totalMinutes * 100f,
                                                    lightSleepPercent =
                                                        session.lightSleepMinutes.toFloat() / totalMinutes * 100f,
                                                    awakePercent = session.awakeMinutes.toFloat() / totalMinutes * 100f,
                                                )
                                            } else {
                                                PercentagesTriple(0f, 0f, 0f, 0f)
                                            }

                                        SleepDetailUiState(
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
                        }
                }.flowOn(Dispatchers.Default)
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = SleepDetailUiState(),
                )
    }
