package app.readylytics.health.feature.sleep

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyRepository
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.data.preferences.AppTheme
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.date.SelectedDateStore
import app.readylytics.health.domain.model.DailyMetrics
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.DailyMetricsRepository
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HeartRateRecordData
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.SleepSessionData
import app.readylytics.health.domain.repository.SleepSessionRepository
import app.readylytics.health.domain.sync.ForegroundSyncGateway
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class SleepViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private val dailySummaryRepository: DailySummaryRepository = mockk(relaxed = true)
    private val dailyMetricsRepository: DailyMetricsRepository = mockk(relaxed = true)
    private val sleepSessionRepository: SleepSessionRepository = mockk(relaxed = true)
    private val heartRateRepository: HeartRateRepository = mockk(relaxed = true)
    private val settingsRepo: UserPreferencesReader = mockk(relaxed = true)
    private val selectedDateRepository: SelectedDateStore = mockk(relaxed = true)
    private val circadianRepo: CircadianConsistencyRepository = mockk(relaxed = true)
    private val foregroundSyncController: ForegroundSyncGateway = mockk(relaxed = true)
    private val savedStateHandle: SavedStateHandle = mockk(relaxed = true)

    private val sleepLayoutRepository: app.readylytics.health.domain.sleep.SleepLayoutRepository = mockk(relaxed = true)

    private val selectedDateFlow = MutableStateFlow(LocalDate.of(2026, 6, 11))
    private val selectedSummaryFlow = MutableStateFlow<DailySummary?>(null)
    private val selectedMetricsFlow = MutableStateFlow<DailyMetrics?>(null)
    private val yesterdaySummaryFlow = MutableStateFlow<DailySummary?>(null)
    private val isSyncingFlow = MutableStateFlow(false)
    private lateinit var viewModel: SleepViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { selectedDateRepository.selectedDate } returns selectedDateFlow
        every { selectedDateRepository.earliestDate } returns MutableStateFlow(null)
        every { circadianRepo.resultFor(any()) } returns flowOf(CircadianConsistencyResult.Calibrating)
        every { foregroundSyncController.isSyncing } returns isSyncingFlow
        every { dailyMetricsRepository.observeByDate(any()) } returns selectedMetricsFlow
        every { settingsRepo.userPreferences } returns flowOf(UserPreferences(goalSleepHours = 8f))

        every { dailySummaryRepository.observeSince(any()) } returns flowOf(emptyList())
        coEvery { dailySummaryRepository.getByDate(any()) } returns null
        every { dailySummaryRepository.observeByDate(any()) } answers {
            val requestedMidnightMs = firstArg<Long>()
            val selectedMidnightMs =
                selectedDateFlow.value
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            when (requestedMidnightMs) {
                selectedMidnightMs -> selectedSummaryFlow
                else -> yesterdaySummaryFlow
            }
        }
        every { sleepSessionRepository.observeSince(any()) } returns flowOf(emptyList())
        every { sleepSessionRepository.observeFirstSessionEndingInRange(any(), any()) } returns flowOf(null)
        every { sleepLayoutRepository.sleepTopCardConfigurations() } returns
            flowOf(app.readylytics.health.data.preferences.SettingsDefaults.DEFAULT_SLEEP_TOP_CARDS)
        every { sleepLayoutRepository.sleepChartConfigurations() } returns
            flowOf(app.readylytics.health.data.preferences.SettingsDefaults.DEFAULT_SLEEP_CHARTS)
        every { sleepLayoutRepository.sleepMetricCardConfigurations() } returns
            flowOf(app.readylytics.health.data.preferences.SettingsDefaults.DEFAULT_SLEEP_METRIC_CARDS)
    }

    @After
    fun tearDown() =
        runTest(testDispatcher) {
            if (::viewModel.isInitialized) {
                viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            }
            Dispatchers.resetMain()
        }

    private fun createViewModel() =
        SleepViewModel(
            dailySummaryRepository = dailySummaryRepository,
            dailyMetricsRepository = dailyMetricsRepository,
            sleepSessionRepository = sleepSessionRepository,
            heartRateRepository = heartRateRepository,
            settingsRepo = settingsRepo,
            selectedDateRepository = selectedDateRepository,
            circadianRepo = circadianRepo,
            foregroundSyncController = foregroundSyncController,
            savedStateHandle = savedStateHandle,
            sleepLayoutRepository = sleepLayoutRepository,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher,
        )

    @Test
    fun `initial state has default trend range and empty points`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.first { !it.isLoading }
            assertEquals(TimeRange.SEVEN_DAYS, state.selectedTrendRange)
            assertEquals(8f, state.goalSleepHours, 0.001f)
            assertEquals(7, state.trendStartOffsetPoints.size)
            assertEquals(7, state.trendDurationSpanPoints.size)
            assertEquals(7, state.trendActualDurationPoints.size)
        }

    @Test
    fun `ui state updates when sleep goal preference changes`() =
        runTest(testDispatcher) {
            val prefsFlow = MutableStateFlow(UserPreferences(goalSleepHours = 7.5f))
            every { settingsRepo.userPreferences } returns prefsFlow

            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            var state = viewModel.uiState.first { !it.isLoading }
            assertEquals(7.5f, state.goalSleepHours, 0.001f)

            prefsFlow.value = UserPreferences(goalSleepHours = 9f)
            testDispatcher.scheduler.advanceUntilIdle()

            state = viewModel.uiState.first { !it.isLoading && it.goalSleepHours == 9f }
            assertEquals(9f, state.goalSleepHours, 0.001f)
        }

    @Test
    fun `ui state exposes sleep time gauge data from current session and sleep goal`() =
        runTest(testDispatcher) {
            val zoneId = ZoneId.systemDefault()
            val selectedDate = LocalDate.of(2026, 6, 11)
            val selectedMidnightMs =
                selectedDate
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val session =
                SleepSessionData(
                    id = "session_1",
                    deviceName = "SmartRing",
                    startTime =
                        selectedDate
                            .minusDays(1)
                            .atTime(22, 0)
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                    endTime =
                        selectedDate
                            .atTime(6, 0)
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                    durationMinutes = 510,
                    efficiency = 0.93f,
                    deepSleepMinutes = 90,
                    lightSleepMinutes = 300,
                    remSleepMinutes = 90,
                    awakeMinutes = 30,
                    sleepScore = 85f,
                )

            coEvery { dailySummaryRepository.getByDate(selectedMidnightMs) } returns
                DailySummary(date = selectedDate, sleepDurationMinutes = 480)
            every { sleepSessionRepository.observeFirstSessionEndingInRange(any(), any()) } returns flowOf(session)
            every { sleepSessionRepository.observeSessionStages(session.id) } returns flowOf(emptyList())
            every { heartRateRepository.observeSleepHrTimelineForSession(session.id) } returns flowOf(emptyList())
            every { settingsRepo.userPreferences } returns flowOf(UserPreferences(goalSleepHours = 8f))

            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.first { !it.isLoading && it.latestSession != null }
            val gaugeData = state.sleepTimeGaugeData
            assertEquals(0.5f, gaugeData.progress!!, 0.001f)
            assertEquals("8h", gaugeData.displayText)
        }

    @Test
    fun `ui state exposes sleep HR samples for the current session`() =
        runTest(testDispatcher) {
            val zoneId = ZoneId.systemDefault()
            val selectedDate = LocalDate.of(2026, 6, 11)
            val session =
                SleepSessionData(
                    id = "session_1",
                    deviceName = "SmartRing",
                    startTime =
                        selectedDate
                            .minusDays(1)
                            .atTime(22, 0)
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                    endTime =
                        selectedDate
                            .atTime(6, 0)
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                    durationMinutes = 480,
                    efficiency = 0.93f,
                    deepSleepMinutes = 90,
                    lightSleepMinutes = 300,
                    remSleepMinutes = 90,
                    awakeMinutes = 30,
                )
            val hrSamples =
                listOf(
                    HeartRateRecordData(
                        id = "hr1",
                        timestampMs = session.startTime + 60_000L,
                        beatsPerMinute = 54,
                        recordType = "SLEEP",
                        sessionId = session.id,
                    ),
                )
            every { sleepSessionRepository.observeFirstSessionEndingInRange(any(), any()) } returns flowOf(session)
            every { sleepSessionRepository.observeSessionStages(session.id) } returns flowOf(emptyList())
            every { heartRateRepository.observeSleepHrTimelineForSession(session.id) } returns flowOf(hrSamples)

            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.first { !it.isLoading && it.latestSession != null }
            assertEquals(hrSamples, state.sleepHrSamples)
        }

    @Test
    fun `ui state has empty sleep HR samples when there is no current session`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.first { !it.isLoading }
            assertEquals(emptyList<HeartRateRecordData>(), state.sleepHrSamples)
        }

    @Test
    fun `onTrendRangeSelected updates selected trend range`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.onTrendRangeSelected(TimeRange.THIRTY_DAYS)
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.first { !it.isLoading && it.selectedTrendRange == TimeRange.THIRTY_DAYS }
            assertEquals(TimeRange.THIRTY_DAYS, state.selectedTrendRange)
            assertEquals(30, state.trendStartOffsetPoints.size)
        }

    @Test
    fun `trend data points are correctly calculated from sleep sessions`() =
        runTest(testDispatcher) {
            val zoneId = ZoneId.systemDefault()
            val session =
                SleepSessionData(
                    id = "session_1",
                    deviceName = "SmartRing",
                    startTime =
                        LocalDate
                            .of(2026, 6, 10)
                            .atTime(22, 0)
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                    endTime =
                        LocalDate
                            .of(2026, 6, 11)
                            .atTime(6, 0)
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                    durationMinutes = 480,
                    efficiency = 0.93f,
                    deepSleepMinutes = 90,
                    lightSleepMinutes = 300,
                    remSleepMinutes = 90,
                    awakeMinutes = 30,
                    sleepScore = 85f,
                )

            every { sleepSessionRepository.observeSince(any()) } returns flowOf(listOf(session))

            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            val state =
                viewModel.uiState.first {
                    !it.isLoading &&
                        it.trendStartOffsetPoints.any { p -> p.value != null }
                }

            val startPoint = state.trendStartOffsetPoints.last()
            val spanPoint = state.trendDurationSpanPoints.last()
            val actualPoint = state.trendActualDurationPoints.last()

            assertEquals(6, startPoint.dayOffset)
            assertEquals(10f, startPoint.value!!, 0.01f)
            assertEquals(8f, spanPoint.value!!, 0.01f)
            assertEquals(8f, actualPoint.value!!, 0.01f)
        }

    @Test
    fun `trend uses core window and total duration for a scoring day with a nap`() =
        runTest(testDispatcher) {
            val zoneId = ZoneId.systemDefault()
            val scoreDay = selectedDateFlow.value
            val coreStart =
                scoreDay
                    .minusDays(1)
                    .atTime(22, 0)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val napStart =
                scoreDay
                    .atTime(13, 0)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val coreEnd =
                scoreDay
                    .atTime(6, 0)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val napEnd =
                scoreDay
                    .atTime(13, 30)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val core =
                sleepSession(
                    id = "core",
                    startTime = coreStart,
                    endTime = coreEnd,
                    durationMinutes = 480,
                )
            val nap =
                sleepSession(
                    id = "nap",
                    startTime = napStart,
                    endTime = napEnd,
                    durationMinutes = 30,
                )
            every { sleepSessionRepository.observeSince(any()) } returns flowOf(listOf(core, nap))

            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.first { !it.isLoading && it.trendActualDurationPoints.last().value != null }
            val dayOffset = TimeRange.SEVEN_DAYS.days - 1
            assertEquals(10f, state.trendStartOffsetPoints[dayOffset].value!!, 0.01f)
            assertEquals(8f, state.trendDurationSpanPoints[dayOffset].value!!, 0.01f)
            assertEquals(8.5f, state.trendActualDurationPoints[dayOffset].value!!, 0.01f)
            assertEquals(listOf(napStart), state.trendDays[dayOffset].naps.map { it.startTimeMs })
        }

    @Test
    fun `trend overlap tie-breaking preserves the session source name`() =
        runTest(testDispatcher) {
            val zoneId = ZoneId.systemDefault()
            val scoreDay = selectedDateFlow.value
            val earlierStart =
                scoreDay
                    .minusDays(1)
                    .atTime(22, 0)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val laterStart =
                scoreDay
                    .minusDays(1)
                    .atTime(22, 30)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val stableIdWinnerWithoutSource =
                sleepSession(
                    id = "a-stable-id",
                    startTime = earlierStart,
                    endTime = earlierStart + 480 * 60_000L,
                    durationMinutes = 480,
                    deviceName = "z-source",
                )
            val sourceWinner =
                sleepSession(
                    id = "z-stable-id",
                    startTime = laterStart,
                    endTime = laterStart + 480 * 60_000L,
                    durationMinutes = 480,
                    deviceName = "a-source",
                )
            every { sleepSessionRepository.observeSince(any()) } returns
                flowOf(listOf(stableIdWinnerWithoutSource, sourceWinner))

            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.first { !it.isLoading && it.trendDays.last().coreStartTimeMs != null }
            assertEquals(laterStart, state.trendDays.last().coreStartTimeMs)
        }

    @Test
    fun `trend derives a positive duration for a legacy zero-duration session`() =
        runTest(testDispatcher) {
            val zoneId = ZoneId.systemDefault()
            val scoreDay = selectedDateFlow.value
            val startTime =
                scoreDay
                    .minusDays(1)
                    .atTime(22, 0)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val session =
                sleepSession(
                    id = "legacy-zero-duration",
                    startTime = startTime,
                    endTime = startTime + 8 * 60 * 60_000L,
                    durationMinutes = 0,
                )
            every { sleepSessionRepository.observeSince(any()) } returns flowOf(listOf(session))

            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.first { !it.isLoading && it.trendDays.last().totalDurationMinutes != null }
            assertEquals(480, state.trendDays.last().totalDurationMinutes)
            assertEquals(8f, state.trendActualDurationPoints.last().value!!, 0.01f)
        }

    @Test
    fun `trend assigns a cutoff-boundary session to its following scoring day`() =
        runTest(testDispatcher) {
            val zoneId = ZoneId.systemDefault()
            val scoreDay = selectedDateFlow.value
            val cutoffStart =
                scoreDay
                    .minusDays(1)
                    .atTime(20, 0)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val session =
                sleepSession(
                    id = "cutoff",
                    startTime = cutoffStart,
                    endTime =
                        scoreDay
                            .minusDays(1)
                            .atTime(20, 30)
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                    durationMinutes = 30,
                )
            every { sleepSessionRepository.observeSince(any()) } returns flowOf(listOf(session))

            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.first { !it.isLoading && it.trendActualDurationPoints.last().value != null }
            assertEquals(0.5f, state.trendActualDurationPoints.last().value!!, 0.01f)
            assertEquals(null, state.trendActualDurationPoints[TimeRange.SEVEN_DAYS.days - 2].value)
        }

    @Test
    fun `trend assigns sessions using the configured scoring zone instead of the device zone`() =
        runTest(testDispatcher) {
            val deviceZoneId = ZoneId.systemDefault()
            val scoreDay = selectedDateFlow.value
            val cutoffMinutes = 20 * 60
            // Device zone may be a UTC-equivalent alias (Etc/UTC, GMT, Iceland, Azores in summer,
            // ...) whose ID differs from "UTC" but has an identical offset. Comparing IDs would pick
            // a scoring zone with the same offset and no instant could diverge, so pick a candidate
            // scoring zone whose actual offset differs from the device zone's.
            val referenceInstant = scoreDay.minusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant()
            val deviceOffsetSeconds = deviceZoneId.rules.getOffset(referenceInstant).totalSeconds
            val scoringZoneId =
                listOf("America/New_York", "Pacific/Kiritimati", "Pacific/Pago_Pago")
                    .asSequence()
                    .map(ZoneId::of)
                    .first { zone ->
                        zone.rules.getOffset(referenceInstant).totalSeconds != deviceOffsetSeconds
                    }
            val sessionStart =
                (0..(48 * 60))
                    .asSequence()
                    .map { minuteOffset ->
                        scoreDay
                            .minusDays(1)
                            .atStartOfDay(ZoneId.of("UTC"))
                            .plusMinutes(minuteOffset.toLong())
                            .toInstant()
                    }.first { instant ->
                        scoreDayFor(instant, scoringZoneId, cutoffMinutes) == scoreDay &&
                            scoreDayFor(instant, deviceZoneId, cutoffMinutes) != scoreDay
                    }.toEpochMilli()
            val session = sleepSession("zone", sessionStart, sessionStart + 30 * 60_000L, 30)
            every { settingsRepo.userPreferences } returns flowOf(UserPreferences(scoringZoneId = scoringZoneId.id))
            every { sleepSessionRepository.observeSince(any()) } returns flowOf(listOf(session))

            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.first { !it.isLoading && it.trendActualDurationPoints.last().value != null }
            assertNotEquals(deviceZoneId, scoringZoneId)
            assertEquals(scoringZoneId, state.trendScoringZoneId)
            assertEquals(0.5f, state.trendActualDurationPoints.last().value!!, 0.01f)
        }

    @Test
    fun `trend data points are padded with null values when no sleep sessions exist`() =
        runTest(testDispatcher) {
            every { sleepSessionRepository.observeSince(any()) } returns flowOf(emptyList())

            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.first { !it.isLoading }
            assertEquals(TimeRange.SEVEN_DAYS, state.selectedTrendRange)
            assertEquals(7, state.trendStartOffsetPoints.size)
            assertEquals(true, state.trendStartOffsetPoints.all { it.value == null })
            assertEquals(true, state.trendDurationSpanPoints.all { it.value == null })
            assertEquals(true, state.trendActualDurationPoints.all { it.value == null })
        }

    @Test
    fun `unrelated pref change does not resubscribe inner flows`() =
        runTest(testDispatcher) {
            val prefsFlow = MutableStateFlow(UserPreferences())
            every { settingsRepo.userPreferences } returns prefsFlow
            var observeSinceCalls = 0
            every { sleepSessionRepository.observeSince(any()) } answers {
                observeSinceCalls++
                flowOf(emptyList())
            }
            var observeFirstSessionCalls = 0
            every { sleepSessionRepository.observeFirstSessionEndingInRange(any(), any()) } answers {
                observeFirstSessionCalls++
                flowOf(null)
            }

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testDispatcher.scheduler.advanceUntilIdle()

            val initialSinceCalls = observeSinceCalls
            val initialFirstSessionCalls = observeFirstSessionCalls
            assertTrue("initial load must subscribe once", initialSinceCalls >= 1)

            prefsFlow.value = UserPreferences(appTheme = AppTheme.DARK)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("unrelated pref change must not restart observeSince", initialSinceCalls, observeSinceCalls)
            assertEquals(
                "unrelated pref change must not restart observeFirstSessionEndingInRange",
                initialFirstSessionCalls,
                observeFirstSessionCalls,
            )

            collectJob.cancelAndJoin()
        }

    @Test
    fun `sleep-relevant pref change resubscribes inner flows`() =
        runTest(testDispatcher) {
            val prefsFlow = MutableStateFlow(UserPreferences())
            every { settingsRepo.userPreferences } returns prefsFlow
            var observeSinceCalls = 0
            every { sleepSessionRepository.observeSince(any()) } answers {
                observeSinceCalls++
                flowOf(emptyList())
            }

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testDispatcher.scheduler.advanceUntilIdle()
            val initialSinceCalls = observeSinceCalls

            prefsFlow.value = UserPreferences(coreMergeGapMinutes = 120)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(initialSinceCalls + 1, observeSinceCalls)

            collectJob.cancelAndJoin()
        }

    private fun sleepSession(
        id: String,
        startTime: Long,
        endTime: Long,
        durationMinutes: Int,
        deviceName: String = "SmartRing",
    ) = SleepSessionData(
        id = id,
        deviceName = deviceName,
        startTime = startTime,
        endTime = endTime,
        durationMinutes = durationMinutes,
        efficiency = 0.93f,
        deepSleepMinutes = 90,
        lightSleepMinutes = 300,
        remSleepMinutes = 90,
        awakeMinutes = 0,
    )

    private fun scoreDayFor(
        instant: java.time.Instant,
        zoneId: ZoneId,
        cutoffMinutes: Int,
    ): LocalDate {
        val localTime = instant.atZone(zoneId)
        val minutesOfDay = localTime.hour * 60 + localTime.minute
        return if (minutesOfDay < cutoffMinutes) localTime.toLocalDate() else localTime.toLocalDate().plusDays(1)
    }

    @Test
    fun `ui state observes yesterday sleep score as rounded reactive value`() =
        runTest(testDispatcher) {
            val selectedDate = selectedDateFlow.value
            val selectedMidnightMs =
                selectedDate
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            selectedSummaryFlow.value = DailySummary(date = selectedDate, sleepScore = 80.2f)
            selectedMetricsFlow.value = DailyMetrics(date = selectedDate, sleepScoreRounded = 80)
            yesterdaySummaryFlow.value = DailySummary(date = selectedDate.minusDays(1), sleepScore = 80.2f)
            coEvery { dailySummaryRepository.getByDate(selectedMidnightMs) } returns selectedSummaryFlow.value

            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            var state =
                viewModel.uiState.first {
                    !it.isLoading &&
                        it.latestSummary?.sleepScore == 80.2f &&
                        it.yesterdaySleepScoreRounded == 80
                }
            assertEquals(80, state.latestMetrics?.sleepScoreRounded)
            assertEquals(80, state.yesterdaySleepScoreRounded)

            yesterdaySummaryFlow.value = DailySummary(date = selectedDate.minusDays(1), sleepScore = 80.6f)
            testDispatcher.scheduler.advanceUntilIdle()

            state =
                viewModel.uiState.first {
                    !it.isLoading &&
                        it.yesterdaySleepScoreRounded == 81
                }
            assertEquals(81, state.yesterdaySleepScoreRounded)
        }

    @Test
    fun `isSyncing toggle does not recompute content, only isLoading and isRefreshing change`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testDispatcher.scheduler.advanceUntilIdle()

            val stateBeforeToggle = viewModel.uiState.value
            assertEquals(false, stateBeforeToggle.isLoading)
            assertEquals(false, stateBeforeToggle.isRefreshing)

            isSyncingFlow.value = true
            testDispatcher.scheduler.advanceUntilIdle()
            val stateSyncing = viewModel.uiState.value
            // No summary/session exists in this fixture, so this is genuinely the first-load
            // case: isLoading correctly stays true.
            assertEquals(true, stateSyncing.isLoading)
            assertEquals(true, stateSyncing.isRefreshing)
            // Only the flags should differ -- the content (trend lists etc.) must be the exact
            // same object, proving the sync toggle did not re-run the heavy day-loop unpacking.
            assertSame(stateBeforeToggle.trendStartOffsetPoints, stateSyncing.trendStartOffsetPoints)

            isSyncingFlow.value = false
            testDispatcher.scheduler.advanceUntilIdle()
            val stateAfterToggle = viewModel.uiState.value
            assertEquals(false, stateAfterToggle.isLoading)
            assertEquals(false, stateAfterToggle.isRefreshing)
            assertSame(stateBeforeToggle.trendStartOffsetPoints, stateAfterToggle.trendStartOffsetPoints)

            collectJob.cancelAndJoin()
        }

    @Test
    fun `isLoading stays false when historical trend data exists even if today's session is missing`() =
        runTest(testDispatcher) {
            // Reproduces the once-per-day gap: today's session/summary haven't landed yet, but a
            // session from a prior day is already in the trend range with real data -- the trend
            // chart has historical data to show, so no skeleton should appear during this sync.
            val zoneId = ZoneId.systemDefault()
            val selectedDate = selectedDateFlow.value
            val priorSession =
                SleepSessionData(
                    id = "session_prior",
                    deviceName = "SmartRing",
                    startTime =
                        selectedDate
                            .minusDays(3)
                            .atTime(22, 0)
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                    endTime =
                        selectedDate
                            .minusDays(2)
                            .atTime(6, 0)
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                    durationMinutes = 480,
                    efficiency = 0.93f,
                    deepSleepMinutes = 90,
                    lightSleepMinutes = 300,
                    remSleepMinutes = 90,
                    awakeMinutes = 30,
                    sleepScore = 85f,
                )
            every { sleepSessionRepository.observeSince(any()) } returns flowOf(listOf(priorSession))
            // observeFirstSessionEndingInRange (today's session) and getByDate/observeByDate
            // (today's summary) stay at their setUp() defaults: null.

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testDispatcher.scheduler.advanceUntilIdle()

            // The provided template for this test checked isRefreshing == true without ever
            // toggling isSyncingFlow -- with the setUp() default of isSyncingFlow.value == false,
            // that assertion could never hold, and isLoading == false is trivially true whenever
            // syncing is false regardless of this fix (isLoading requires syncing && !hasData).
            // Toggling isSyncingFlow to true here is what actually exercises the fix: it proves
            // isLoading stays false *during* a sync, once the trend already has a real historical
            // point from the prior-day session.
            isSyncingFlow.value = true
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.first { it.trendStartOffsetPoints.any { p -> p.value != null } }
            assertEquals(false, state.isLoading)
            assertEquals(true, state.isRefreshing)

            collectJob.cancelAndJoin()
        }

    @Test
    fun `isLoading stays false and isRefreshing toggles when sleep data is present`() =
        runTest(testDispatcher) {
            val zoneId = ZoneId.systemDefault()
            val selectedDate = selectedDateFlow.value
            val session =
                SleepSessionData(
                    id = "session_1",
                    deviceName = "SmartRing",
                    startTime =
                        selectedDate
                            .minusDays(1)
                            .atTime(22, 0)
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                    endTime =
                        selectedDate
                            .atTime(6, 0)
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                    durationMinutes = 480,
                    efficiency = 0.93f,
                    deepSleepMinutes = 90,
                    lightSleepMinutes = 300,
                    remSleepMinutes = 90,
                    awakeMinutes = 30,
                    sleepScore = 85f,
                )
            every { sleepSessionRepository.observeFirstSessionEndingInRange(any(), any()) } returns flowOf(session)
            every { sleepSessionRepository.observeSessionStages(session.id) } returns flowOf(emptyList())
            every { heartRateRepository.observeSleepHrTimelineForSession(session.id) } returns flowOf(emptyList())
            // Also feed the same session into the trend query (observeSince), which is what
            // isLoading is now based on -- without this, the trend list stays empty (setUp()
            // default) and isLoading would (correctly, per the fix) be true while syncing, since
            // "today has a session" no longer implies "the chart has historical data to show".
            every { sleepSessionRepository.observeSince(any()) } returns flowOf(listOf(session))

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testDispatcher.scheduler.advanceUntilIdle()

            val stateBeforeToggle = viewModel.uiState.first { it.latestSession != null }
            assertEquals(false, stateBeforeToggle.isLoading)
            assertEquals(false, stateBeforeToggle.isRefreshing)

            isSyncingFlow.value = true
            testDispatcher.scheduler.advanceUntilIdle()
            val stateSyncing = viewModel.uiState.value
            assertEquals(false, stateSyncing.isLoading)
            assertEquals(true, stateSyncing.isRefreshing)

            isSyncingFlow.value = false
            testDispatcher.scheduler.advanceUntilIdle()
            val stateAfterToggle = viewModel.uiState.value
            assertEquals(false, stateAfterToggle.isLoading)
            assertEquals(false, stateAfterToggle.isRefreshing)

            collectJob.cancelAndJoin()
        }
}
