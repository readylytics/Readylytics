package app.readylytics.health.domain.scoring.golden

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.data.local.HealthDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * De-risks [GoldenFixtureDataBuilder] in isolation before it feeds the much more expensive
 * [GoldenFixtureWalkForwardTest]: confirms each required scenario actually lands in the seeded
 * database with the expected shape.
 */
@RunWith(AndroidJUnit4::class)
class GoldenFixtureDataBuilderTest {
    private val zoneId: ZoneId = ZoneId.of("Europe/Berlin")
    private val startDate: LocalDate = LocalDate.of(2024, 6, 1)
    private val endDate: LocalDate = LocalDate.of(2024, 10, 1)

    private lateinit var db: HealthDatabase

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
                .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `builder produces exactly one stage-less night`() =
        runTest {
            val result = GoldenFixtureDataBuilder(zoneId).build(db, startDate, endDate)
            val stageLessNightDate = result.scenarioDates.stageLessNightDate
            val session =
                db
                    .sleepSessionDao()
                    .getOverlapping(
                        stageLessNightDate
                            .minusDays(1)
                            .atTime(12, 0)
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                        stageLessNightDate
                            .atTime(12, 0)
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                    ).single { it.id.endsWith("_main") }

            assertEquals(0, session.durationMinutes)
            assertEquals(0f, session.efficiency)
        }

    @Test
    fun `builder produces a biphasic day with two sleep sessions`() =
        runTest {
            val result = GoldenFixtureDataBuilder(zoneId).build(db, startDate, endDate)
            val biphasicDate = result.scenarioDates.biphasicDate
            val sessions =
                db
                    .sleepSessionDao()
                    .getOverlapping(
                        biphasicDate
                            .minusDays(1)
                            .atStartOfDay(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                        biphasicDate
                            .atTime(23, 59)
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                    )

            assertTrue(sessions.any { it.id.endsWith("_main") })
            assertTrue(sessions.any { it.id.endsWith("_nap") })
        }

    @Test
    fun `builder produces a multi-day data gap with zero steps and no rows`() =
        runTest {
            val result = GoldenFixtureDataBuilder(zoneId).build(db, startDate, endDate)
            val gapStart = result.scenarioDates.gapStart
            val gapEnd = result.scenarioDates.gapEnd

            assertEquals(0L, result.stepsByDate[gapStart])
            assertEquals(0L, result.stepsByDate[gapEnd])

            val sessionsInGap =
                db
                    .sleepSessionDao()
                    .getOverlapping(
                        gapStart
                            .atTime(12, 0)
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                        gapEnd
                            .atTime(20, 0)
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                    )
            assertTrue(sessionsInGap.isEmpty(), "expected no sleep sessions inside the seeded data gap")
        }

    @Test
    fun `builder covers both DST scenario dates`() =
        runTest {
            val dstStart = LocalDate.of(2025, 1, 1)
            val dstEnd = LocalDate.of(2025, 12, 31)
            val result = GoldenFixtureDataBuilder(zoneId).build(db, dstStart, dstEnd)

            for (dstDate in result.scenarioDates.dstDates) {
                val sessions =
                    db
                        .sleepSessionDao()
                        .getOverlapping(
                            dstDate
                                .minusDays(1)
                                .atStartOfDay(zoneId)
                                .toInstant()
                                .toEpochMilli(),
                            dstDate
                                .atTime(23, 59)
                                .atZone(zoneId)
                                .toInstant()
                                .toEpochMilli(),
                        )
                assertTrue(sessions.isNotEmpty(), "expected a sleep session covering DST date $dstDate")
            }
        }
}
