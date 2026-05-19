package com.gregor.lauritz.healthdashboard.ui.sleep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.model.SleepTypicalRanges
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class SleepDetailUiState(
    val session: SleepSessionEntity? = null,
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
    ) : ViewModel() {
        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<SleepDetailUiState> =
            selectedDateRepository.selectedDate
                .flatMapLatest { date ->
                    val startDateTime = date.atStartOfDay(ZoneId.systemDefault())
                    val startDayMs = startDateTime.toInstant().toEpochMilli()
                    val endDayMs = startDateTime.plusDays(1).toInstant().toEpochMilli()

                    sleepSessionDao.observeFirstSessionEndingInRange(startDayMs, endDayMs)
                        .map { session ->
                            val totalMinutes = session?.let {
                                it.deepSleepMinutes + it.remSleepMinutes + it.lightSleepMinutes + it.awakeMinutes
                            } ?: 0

                            val deepPercent = if (totalMinutes > 0) {
                                session!!.deepSleepMinutes.toFloat() / totalMinutes * 100f
                            } else {
                                0f
                            }
                            val remPercent = if (totalMinutes > 0) {
                                session!!.remSleepMinutes.toFloat() / totalMinutes * 100f
                            } else {
                                0f
                            }
                            val lightPercent = if (totalMinutes > 0) {
                                session!!.lightSleepMinutes.toFloat() / totalMinutes * 100f
                            } else {
                                0f
                            }
                            val awakePercent = if (totalMinutes > 0) {
                                session!!.awakeMinutes.toFloat() / totalMinutes * 100f
                            } else {
                                0f
                            }

                            SleepDetailUiState(
                                session = session,
                                deepSleepPercent = deepPercent,
                                remSleepPercent = remPercent,
                                lightSleepPercent = lightPercent,
                                awakePercent = awakePercent,
                                sleepScore = session?.sleepScore,
                                typicalRanges = SleepTypicalRanges.DEFAULT,
                                selectedDate = date,
                            )
                        }
                }
                .flowOn(Dispatchers.Default)
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = SleepDetailUiState(),
                )
    }
