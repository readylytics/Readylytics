package com.gregor.lauritz.healthdashboard.domain.scoring

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class TimezoneValidatorTest {
    private val validator = TimezoneValidator()

    private val pst = -8 * 3600
    private val pdt = -7 * 3600
    private val jst = 9 * 3600
    private val cet = 1 * 3600
    private val cest = 2 * 3600

    // ─── No / small change ─────────────────────────────────────────────────

    @Test
    fun `identical offsets are OK`() {
        assertEquals(
            TimezoneValidator.TimezoneValidation.OK,
            validator.validateTimezoneOffset(pst, pst, LocalDate.of(2024, 3, 10)),
        )
    }

    @Test
    fun `null previous offset is treated as OK`() {
        assertEquals(
            TimezoneValidator.TimezoneValidation.OK,
            validator.validateTimezoneOffset(pst, null, LocalDate.of(2024, 6, 1)),
        )
    }

    // ─── DST transitions ──────────────────────────────────────────────────

    @Test
    fun `US PST to PDT on 2nd Sunday March is DST_TRANSITION`() {
        // 2024-03-10 is 2nd Sunday of March (US DST)
        val date = LocalDate.of(2024, 3, 10)
        assertEquals(
            TimezoneValidator.TimezoneValidation.DST_TRANSITION,
            validator.validateTimezoneOffset(pdt, pst, date),
        )
    }

    @Test
    fun `US PDT to PST on 1st Sunday November is DST_TRANSITION`() {
        // 2024-11-03 is 1st Sunday of November
        val date = LocalDate.of(2024, 11, 3)
        assertEquals(
            TimezoneValidator.TimezoneValidation.DST_TRANSITION,
            validator.validateTimezoneOffset(pst, pdt, date),
        )
    }

    @Test
    fun `EU CET to CEST on last Sunday March is DST_TRANSITION`() {
        // 2024-03-31 is last Sunday of March (EU DST)
        val date = LocalDate.of(2024, 3, 31)
        assertEquals(
            TimezoneValidator.TimezoneValidation.DST_TRANSITION,
            validator.validateTimezoneOffset(cest, cet, date),
        )
    }

    @Test
    fun `1h shift on non-DST date is TIMEZONE_JUMP`() {
        // Random Sunday in June
        val date = LocalDate.of(2024, 6, 16)
        assertEquals(
            TimezoneValidator.TimezoneValidation.TIMEZONE_JUMP,
            validator.validateTimezoneOffset(pdt, pst, date),
        )
    }

    // ─── True cross-region travel ─────────────────────────────────────────

    @Test
    fun `PST to JST is TIMEZONE_JUMP regardless of date`() {
        val date = LocalDate.of(2024, 3, 10) // even a DST boundary
        assertEquals(
            TimezoneValidator.TimezoneValidation.TIMEZONE_JUMP,
            validator.validateTimezoneOffset(jst, pst, date),
        )
    }

    @Test
    fun `west-to-east 17h jump detected`() {
        val date = LocalDate.of(2024, 6, 1)
        assertEquals(
            TimezoneValidator.TimezoneValidation.TIMEZONE_JUMP,
            validator.validateTimezoneOffset(jst, pst, date),
        )
    }

    // ─── localDate helper ─────────────────────────────────────────────────

    @Test
    fun `localDate uses stored offset rather than UTC`() {
        // 2024-06-01 03:30 UTC == 2024-05-31 19:30 PST (offset -8h)
        val instant =
            ZonedDateTime
                .of(2024, 6, 1, 3, 30, 0, 0, ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        val ld = validator.localDate(instant, pst)
        assertEquals(LocalDate.of(2024, 5, 31), ld)
    }

    @Test
    fun `localDate with null offset falls back to system default`() {
        val instant =
            ZonedDateTime
                .of(2024, 6, 1, 12, 0, 0, 0, ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        val ld = validator.localDate(instant, null)
        assertEquals(LocalDate.of(2024, 6, 1), ld)
    }
}
