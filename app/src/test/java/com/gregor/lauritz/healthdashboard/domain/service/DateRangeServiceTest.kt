package com.gregor.lauritz.healthdashboard.domain.service

import com.gregor.lauritz.healthdashboard.domain.model.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class DateRangeServiceTest {
    // Fixed clock: 2024-06-15 12:00 UTC
    private val fixedInstant: Instant = Instant.parse("2024-06-15T12:00:00Z")
    private val utc: ZoneId = ZoneOffset.UTC
    private val clock: Clock = Clock.fixed(fixedInstant, utc)
    private lateinit var service: DateRangeService

    @Before
    fun setUp() {
        service = DateRangeService(clock)
    }

    private fun rangeEndingTodayOf(days: Int): DateRange =
        (service.rangeEndingToday(days) as Result.Success).data

    private fun rangeEndingOf(
        end: LocalDate,
        days: Int,
    ): DateRange = (service.rangeEnding(end, days) as Result.Success).data

    // ─── today / range endpoints ─────────────────────────────────────────────
    @Test
    fun today_usesInjectedClock() {
        assertEquals(LocalDate.of(2024, 6, 15), service.today())
    }

    @Test
    fun rangeEndingToday_30days_correctEnd() {
        val r = rangeEndingTodayOf(30)
        assertEquals(LocalDate.of(2024, 6, 15), r.end)
    }

    @Test
    fun rangeEndingToday_30days_correctStart() {
        val r = rangeEndingTodayOf(30)
        assertEquals(LocalDate.of(2024, 5, 17), r.start)
    }

    @Test
    fun rangeEndingToday_30days_hasCorrectDayCount() {
        val r = rangeEndingTodayOf(30)
        assertEquals(30, r.days)
    }

    @Test
    fun rangeEndingToday_1day_isSingleDate() {
        val r = rangeEndingTodayOf(1)
        assertEquals(1, r.days)
        assertEquals(r.start, r.end)
    }

    @Test
    fun rangeEndingToday_zero_returnsFailure() {
        val r = service.rangeEndingToday(0)
        assertTrue(r is Result.Failure)
        assertEquals(DateRangeService.Codes.NON_POSITIVE_DAYS, (r as Result.Failure).code)
    }

    @Test
    fun rangeEndingToday_negative_returnsFailure() {
        val r = service.rangeEndingToday(-5)
        assertTrue(r is Result.Failure)
    }

    @Test
    fun rangeEnding_explicitEnd_buildsRange() {
        val end = LocalDate.of(2024, 1, 31)
        val r = rangeEndingOf(end, 7)
        assertEquals(LocalDate.of(2024, 1, 25), r.start)
        assertEquals(end, r.end)
    }

    @Test
    fun rangeEnding_zero_returnsFailure() {
        val r = service.rangeEnding(LocalDate.of(2024, 1, 1), 0)
        assertTrue(r is Result.Failure)
        assertEquals(DateRangeService.Codes.NON_POSITIVE_DAYS, (r as Result.Failure).code)
    }

    // ─── named windows ───────────────────────────────────────────────────────
    @Test
    fun baselineWindow_is30Days() = assertEquals(30, (service.baselineWindow() as Result.Success).data.days)

    @Test
    fun acuteWindow_is7Days() = assertEquals(7, (service.acuteWindow() as Result.Success).data.days)

    @Test
    fun chronicWindow_is42Days() = assertEquals(42, (service.chronicWindow() as Result.Success).data.days)

    @Test
    fun baselineWindow_endsToday() =
        assertEquals(service.today(), (service.baselineWindow() as Result.Success).data.end)

    @Test
    fun acuteWindow_endsToday() = assertEquals(service.today(), (service.acuteWindow() as Result.Success).data.end)

    @Test
    fun chronicWindow_endsToday() =
        assertEquals(service.today(), (service.chronicWindow() as Result.Success).data.end)

    @Test
    fun acuteWindow_startIsSixDaysBack() {
        assertEquals(LocalDate.of(2024, 6, 9), (service.acuteWindow() as Result.Success).data.start)
    }

    @Test
    fun chronicWindow_startIs41DaysBack() {
        assertEquals(LocalDate.of(2024, 5, 5), (service.chronicWindow() as Result.Success).data.start)
    }

    // ─── isCalibrating ───────────────────────────────────────────────────────
    @Test
    fun isCalibrating_lessThan7_isTrue() = assertTrue(service.isCalibrating(0))

    @Test
    fun isCalibrating_at7_isFalse() = assertFalse(service.isCalibrating(7))

    @Test
    fun isCalibrating_above7_isFalse() = assertFalse(service.isCalibrating(30))

    @Test
    fun isCalibrating_6_isTrue() = assertTrue(service.isCalibrating(6))

    // ─── daysIn / daysBetween / isWithin ─────────────────────────────────────
    @Test
    fun daysIn_returnsCorrectCount() {
        val r =
            (DateRange.create(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 10)) as Result.Success).data
        assertEquals(10, service.daysIn(r))
    }

    @Test
    fun daysBetween_forwardIsPositive() {
        assertEquals(5L, service.daysBetween(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 6)))
    }

    @Test
    fun daysBetween_backwardIsNegative() {
        assertEquals(-5L, service.daysBetween(LocalDate.of(2024, 1, 6), LocalDate.of(2024, 1, 1)))
    }

    @Test
    fun daysBetween_sameDay_isZero() {
        assertEquals(0L, service.daysBetween(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1)))
    }

    @Test
    fun isWithin_startDay_isTrue() {
        val r =
            (DateRange.create(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 10)) as Result.Success).data
        assertTrue(service.isWithin(LocalDate.of(2024, 1, 1), r))
    }

    @Test
    fun isWithin_endDay_isTrue() {
        val r =
            (DateRange.create(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 10)) as Result.Success).data
        assertTrue(service.isWithin(LocalDate.of(2024, 1, 10), r))
    }

    @Test
    fun isWithin_outsideBefore_isFalse() {
        val r =
            (DateRange.create(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 10)) as Result.Success).data
        assertFalse(service.isWithin(LocalDate.of(2023, 12, 31), r))
    }

    @Test
    fun isWithin_outsideAfter_isFalse() {
        val r =
            (DateRange.create(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 10)) as Result.Success).data
        assertFalse(service.isWithin(LocalDate.of(2024, 1, 11), r))
    }

    @Test
    fun expandToDates_returnsAllDays() {
        val r =
            (DateRange.create(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 3)) as Result.Success).data
        assertEquals(
            listOf(
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 1, 2),
                LocalDate.of(2024, 1, 3),
            ),
            service.expandToDates(r),
        )
    }

    @Test
    fun expandToDates_singleDay_isSingletonList() {
        val r =
            (DateRange.create(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1)) as Result.Success).data
        assertEquals(listOf(LocalDate.of(2024, 1, 1)), service.expandToDates(r))
    }

    // ─── epoch-millis bridges ────────────────────────────────────────────────
    @Test
    fun toLocalDate_fromInstant_returnsExpected() {
        val ms = Instant.parse("2024-06-15T00:00:00Z").toEpochMilli()
        assertEquals(LocalDate.of(2024, 6, 15), service.toLocalDate(ms))
    }

    @Test
    fun startOfDayMillis_isMidnight() {
        val date = LocalDate.of(2024, 6, 15)
        val expected = date.atStartOfDay(utc).toInstant().toEpochMilli()
        assertEquals(expected, service.startOfDayMillis(date))
    }

    @Test
    fun endOfDayMillis_isJustBeforeNextMidnight() {
        val date = LocalDate.of(2024, 6, 15)
        val nextDay = date.plusDays(1).atStartOfDay(utc).toInstant().toEpochMilli()
        assertEquals(nextDay - 1, service.endOfDayMillis(date))
    }

    @Test
    fun truncateToDay_returnsStartOfDay() {
        val midDay = Instant.parse("2024-06-15T15:30:00Z").toEpochMilli()
        val expected = LocalDate.of(2024, 6, 15).atStartOfDay(utc).toInstant().toEpochMilli()
        assertEquals(expected, service.truncateToDay(midDay))
    }

    @Test
    fun toEpochMillisRange_returnsPair() {
        val r =
            (DateRange.create(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1)) as Result.Success).data
        val (start, end) = r.toEpochMillisRange(utc)
        val expectedStart = LocalDate.of(2024, 1, 1).atStartOfDay(utc).toInstant().toEpochMilli()
        assertEquals(expectedStart, start)
        assertEquals(expectedStart + 24L * 60 * 60 * 1000 - 1, end)
    }

    // ─── overlaps ────────────────────────────────────────────────────────────
    @Test
    fun overlaps_identicalRanges_isTrue() {
        val a =
            (DateRange.create(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5)) as Result.Success).data
        assertTrue(a.overlaps(a))
    }

    @Test
    fun overlaps_partialOverlap_isTrue() {
        val a =
            (DateRange.create(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5)) as Result.Success).data
        val b =
            (DateRange.create(LocalDate.of(2024, 1, 4), LocalDate.of(2024, 1, 10)) as Result.Success).data
        assertTrue(a.overlaps(b))
    }

    @Test
    fun overlaps_touchingEndpoints_isTrue() {
        val a =
            (DateRange.create(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5)) as Result.Success).data
        val b =
            (DateRange.create(LocalDate.of(2024, 1, 5), LocalDate.of(2024, 1, 10)) as Result.Success).data
        assertTrue(a.overlaps(b))
    }

    @Test
    fun overlaps_disjoint_isFalse() {
        val a =
            (DateRange.create(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5)) as Result.Success).data
        val b =
            (DateRange.create(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 5)) as Result.Success).data
        assertFalse(a.overlaps(b))
    }

    // ─── slidingWindows ──────────────────────────────────────────────────────
    @Test
    fun slidingWindows_size7_over10days_produces4Windows() {
        val r =
            (DateRange.create(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 10)) as Result.Success).data
        val w = (service.slidingWindows(r, 7) as Result.Success).data
        assertEquals(4, w.size)
    }

    @Test
    fun slidingWindows_windowEqualsRange_producesOneWindow() {
        val r =
            (DateRange.create(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5)) as Result.Success).data
        val w = (service.slidingWindows(r, 5) as Result.Success).data
        assertEquals(1, w.size)
        assertEquals(r, w.first())
    }

    @Test
    fun slidingWindows_windowLargerThanRange_isEmpty() {
        val r =
            (DateRange.create(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5)) as Result.Success).data
        val w = (service.slidingWindows(r, 6) as Result.Success).data
        assertTrue(w.isEmpty())
    }

    @Test
    fun slidingWindows_window1_returnsEveryDay() {
        val r =
            (DateRange.create(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 3)) as Result.Success).data
        val w = (service.slidingWindows(r, 1) as Result.Success).data
        assertEquals(3, w.size)
        assertEquals(LocalDate.of(2024, 1, 1), w.first().start)
        assertEquals(LocalDate.of(2024, 1, 3), w.last().end)
    }

    @Test
    fun slidingWindows_zeroWindow_returnsFailure() {
        val r =
            (DateRange.create(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5)) as Result.Success).data
        val w = service.slidingWindows(r, 0)
        assertTrue(w is Result.Failure)
    }

    // ─── DateRange invariants ────────────────────────────────────────────────
    @Test
    fun dateRange_endBeforeStart_returnsFailure() {
        val r = DateRange.create(LocalDate.of(2024, 1, 10), LocalDate.of(2024, 1, 1))
        assertTrue(r is Result.Failure)
        assertEquals(DateRangeService.Codes.END_BEFORE_START, (r as Result.Failure).code)
    }

    @Test
    fun dateRange_sameStartAndEnd_isOneDay() {
        val r =
            (DateRange.create(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1)) as Result.Success).data
        assertEquals(1, r.days)
    }

    @Test
    fun dateRange_contains_extension() {
        val r =
            (DateRange.create(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5)) as Result.Success).data
        assertTrue(r.contains(LocalDate.of(2024, 1, 3)))
        assertFalse(r.contains(LocalDate.of(2024, 1, 6)))
    }

    @Test
    fun dateRange_toDateList_matchesDays() {
        val r =
            (DateRange.create(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5)) as Result.Success).data
        assertEquals(5, r.toDateList().size)
    }

    // ─── durationOf ──────────────────────────────────────────────────────────
    @Test
    fun durationOf_oneDayRange_isOneDay() {
        val r =
            (DateRange.create(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1)) as Result.Success).data
        assertEquals(1L, service.durationOf(r).toDays())
    }

    @Test
    fun durationOf_thirtyDayRange_isThirtyDays() {
        val r =
            (DateRange.create(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 30)) as Result.Success).data
        assertEquals(30L, service.durationOf(r).toDays())
    }

    // ─── constants ───────────────────────────────────────────────────────────
    @Test
    fun constants_haveExpectedValues() {
        assertEquals(30, DateRangeService.BASELINE_DAYS)
        assertEquals(7, DateRangeService.ACUTE_DAYS)
        assertEquals(42, DateRangeService.CHRONIC_DAYS)
        assertEquals(7, DateRangeService.MIN_BASELINE_DAYS)
    }

    // ─── month / year boundaries ─────────────────────────────────────────────
    @Test
    fun rangeEnding_acrossMonthBoundary_correctStart() {
        val end = LocalDate.of(2024, 3, 5)
        val r = rangeEndingOf(end, 10)
        assertEquals(LocalDate.of(2024, 2, 25), r.start)
    }

    @Test
    fun rangeEnding_acrossYearBoundary_correctStart() {
        val end = LocalDate.of(2024, 1, 5)
        val r = rangeEndingOf(end, 10)
        assertEquals(LocalDate.of(2023, 12, 27), r.start)
    }

    @Test
    fun leapYear_feb29_isHandled() {
        val end = LocalDate.of(2024, 2, 29)
        val r = rangeEndingOf(end, 30)
        assertEquals(LocalDate.of(2024, 1, 31), r.start)
    }

    // ─── default-clock constructor exists ────────────────────────────────────
    @Test
    fun defaultConstructor_usesSystemClock_today_isReasonable() {
        val def = DateRangeService()
        val now = LocalDate.now()
        val t = def.today()
        assertTrue(t == now || t == now.minusDays(1) || t == now.plusDays(1))
    }
}
