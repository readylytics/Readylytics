package com.gregor.lauritz.healthdashboard.data.repository

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WidgetDataRepositoryTest {
    private val dailySummaryDao = mockk<DailySummaryDao>()
    private val sleepSessionDao = mockk<SleepSessionDao>()
    private lateinit var repository: WidgetDataRepository

    @Before
    fun setup() {
        repository = WidgetDataRepository(dailySummaryDao, sleepSessionDao)
    }

    @Test
    fun observeLatestSummary_returns_most_recent_summary() =
        runTest {
            val mockEntity = mockk<com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity>()
            coEvery { dailySummaryDao.observeLatest() } returns flowOf(mockEntity)

            val result = repository.observeLatestSummary().first()

            assertNotNull(result)
        }

    @Test
    fun observeLatestSummary_returns_null_when_no_data() =
        runTest {
            coEvery { dailySummaryDao.observeLatest() } returns flowOf(null)

            val result = repository.observeLatestSummary().first()

            assertNull(result)
        }

    @Test
    fun observeSummaryByDate_returns_summary_for_specific_date() =
        runTest {
            val mockEntity = mockk<com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity>()
            val dateMidnightMs = 1609459200000L // 2021-01-01 00:00:00 UTC

            coEvery { dailySummaryDao.observeByDate(dateMidnightMs) } returns flowOf(mockEntity)

            val result = repository.observeSummaryByDate(dateMidnightMs).first()

            assertNotNull(result)
        }

    @Test
    fun observeSummaryByDate_returns_null_when_no_data_for_date() =
        runTest {
            val dateMidnightMs = 1609459200000L

            coEvery { dailySummaryDao.observeByDate(dateMidnightMs) } returns flowOf(null)

            val result = repository.observeSummaryByDate(dateMidnightMs).first()

            assertNull(result)
        }

    @Test
    fun getLatestSummaryAsync_returns_current_data() =
        runTest {
            val mockEntity = mockk<com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity>()
            coEvery { dailySummaryDao.getLatestAsync() } returns mockEntity

            val result = repository.getLatestSummaryAsync()

            assertNotNull(result)
        }

    @Test
    fun getLatestSummaryAsync_returns_null_when_no_data() =
        runTest {
            coEvery { dailySummaryDao.getLatestAsync() } returns null

            val result = repository.getLatestSummaryAsync()

            assertNull(result)
        }

    @Test
    fun getSummaryByDateAsync_returns_summary_for_date() =
        runTest {
            val dateMidnightMs = 1609459200000L
            val mockEntity = mockk<com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity>()

            coEvery { dailySummaryDao.getByDateAsync(dateMidnightMs) } returns mockEntity

            val result = repository.getSummaryByDateAsync(dateMidnightMs)

            assertNotNull(result)
        }

    @Test
    fun getSummaryByDateAsync_returns_null_when_no_data() =
        runTest {
            val dateMidnightMs = 1609459200000L
            coEvery { dailySummaryDao.getByDateAsync(dateMidnightMs) } returns null

            val result = repository.getSummaryByDateAsync(dateMidnightMs)

            assertNull(result)
        }

    @Test
    fun observeSince_returns_multiple_summaries() =
        runTest {
            val fromMidnightMs = 1609459200000L
            val mockEntities =
                listOf(
                    mockk<com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity>(),
                    mockk<com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity>(),
                    mockk<com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity>(),
                )

            coEvery { dailySummaryDao.observeSince(fromMidnightMs) } returns flowOf(mockEntities)

            val result = repository.observeSince(fromMidnightMs).first()

            assertEquals(3, result.size)
        }

    @Test
    fun observeSince_returns_empty_list_when_no_data() =
        runTest {
            val fromMidnightMs = 1609459200000L
            coEvery { dailySummaryDao.observeSince(fromMidnightMs) } returns flowOf(emptyList())

            val result = repository.observeSince(fromMidnightMs).first()

            assertEquals(0, result.size)
        }
}
