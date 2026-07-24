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
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lightweight, fast-running check that [SyntheticDatasetGenerator] produces the configured row
 * density and date coverage. Uses a small [SyntheticDatasetGenerator.Config] -- the default
 * ~1M-row configuration is exercised by the WP-02b instrumented benchmark, not here, since a
 * million-row generation is too slow for the regular unit-test loop.
 */
@RunWith(AndroidJUnit4::class)
class SyntheticDatasetGeneratorTest {
    private val zoneId: ZoneId = ZoneId.of("Europe/Berlin")
    private lateinit var dbFile: File
    private lateinit var db: HealthDatabase

    @Before
    fun setUp() {
        dbFile = File.createTempFile("synthetic-dataset-test", ".db")
        dbFile.delete()
        db =
            Room
                .databaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                    dbFile.absolutePath,
                ).build()
    }

    @After
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun `generator produces the configured row density and date coverage`() =
        runTest {
            val endDate = LocalDate.of(2026, 1, 1)
            val config =
                SyntheticDatasetGenerator.Config(
                    historyDays = 60,
                    denseWindowDays = 7,
                    heartRateSamplesInDenseWindow = 2_000,
                    sleepSessionCount = 20,
                    workoutCount = 10,
                )

            val summary = SyntheticDatasetGenerator(zoneId).generate(db, endDate, config)

            assertEquals(20, summary.sleepSessionCount)
            assertEquals(10, summary.workoutCount)
            assertTrue(
                summary.heartRateRowCount in 1_900..2_100,
                "expected ~2000 HR rows, got ${summary.heartRateRowCount}",
            )
            assertEquals(20, summary.hrvRowCount.toInt())
            assertEquals(endDate.minusDays(59), summary.historyStartDate)
            assertEquals(endDate.minusDays(6), summary.denseWindowStartDate)
        }
}
