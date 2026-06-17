package app.readylytics.health.domain.model

import app.readylytics.health.data.local.entity.DailySummaryEntity
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

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
}
