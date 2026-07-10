package app.readylytics.health.domain.model

import app.readylytics.health.data.local.entity.DailySummaryEntity
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DailySummaryMapperTest {
    @Test
    fun toDomainUsesExplicitScoringZone() {
        val scoringZone = ZoneId.of("Europe/Helsinki")
        val scoringDate = LocalDate.of(2026, 2, 15)
        val entity =
            DailySummaryEntity(
                dateMidnightMs =
                    scoringDate
                        .atStartOfDay(scoringZone)
                        .toInstant()
                        .toEpochMilli(),
            )

        val domain = DailySummaryMapper.toDomain(entity, scoringZone)

        assertEquals(scoringDate, domain.date)
    }

    @Test
    fun toEntityUsesExplicitScoringZone() {
        val scoringZone = ZoneId.of("Europe/Helsinki")
        val scoringDate = LocalDate.of(2026, 2, 15)
        val domain = DailySummary(date = scoringDate)

        val entity = DailySummaryMapper.toEntity(domain, scoringZone)

        assertEquals(
            scoringDate
                .atStartOfDay(scoringZone)
                .toInstant()
                .toEpochMilli(),
            entity.dateMidnightMs,
        )
    }

    @Test
    fun toEntityPreservesCalibratingState() {
        val scoringZone = ZoneId.of("Europe/Berlin")
        val scoringDate = LocalDate.of(2026, 3, 29)
        val domain = DailySummary(date = scoringDate, isCalibrating = true)

        val entity = DailySummaryMapper.toEntity(domain, scoringZone)
        val roundTrip = DailySummaryMapper.toDomain(entity, scoringZone)

        assertTrue(entity.isCalibrating == true)
        assertTrue(roundTrip.isCalibrating)
    }

    @Test
    fun toEntityPreservesNonCalibratingState() {
        val scoringZone = ZoneId.of("Europe/Berlin")
        val scoringDate = LocalDate.of(2026, 3, 29)
        val domain = DailySummary(date = scoringDate, isCalibrating = false)

        val entity = DailySummaryMapper.toEntity(domain, scoringZone)
        val roundTrip = DailySummaryMapper.toDomain(entity, scoringZone)

        assertTrue(entity.isCalibrating == false)
        assertFalse(roundTrip.isCalibrating)
    }

    @Test
    fun toEntityPreservesNapFields() {
        val scoringZone = ZoneId.of("Europe/Berlin")
        val scoringDate = LocalDate.of(2026, 3, 29)
        val domain =
            DailySummary(
                date = scoringDate,
                supplementalSleepDurationMinutes = 45,
                napCount = 2,
            )

        val entity = DailySummaryMapper.toEntity(domain, scoringZone)
        val roundTrip = DailySummaryMapper.toDomain(entity, scoringZone)

        assertEquals(45, entity.supplementalSleepDurationMinutes)
        assertEquals(2, entity.napCount)
        assertEquals(45, roundTrip.supplementalSleepDurationMinutes)
        assertEquals(2, roundTrip.napCount)
    }
}
