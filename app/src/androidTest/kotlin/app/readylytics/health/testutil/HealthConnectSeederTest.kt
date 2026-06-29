package app.readylytics.health.testutil

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.reflect.KClass

/**
 * Instrumented tests for [seedNocturnalData].
 *
 * Requires a real Health Connect provider (Android 14+ / API 36 emulator).
 * Tests are skipped automatically via [assumeTrue] when HC is unavailable.
 *
 * Permission note: both WRITE_HEART_RATE and WRITE_RESTING_HEART_RATE are declared because
 * the exact permission string for [RestingHeartRateRecord] varies by HC SDK build.
 * Use `HealthPermission.getWritePermission(RestingHeartRateRecord::class)` at runtime
 * to discover the canonical string if a PermissionRequiredException occurs.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class HealthConnectSeederTest {
    @get:Rule
    val grantPermissions: GrantPermissionRule =
        GrantPermissionRule.grant(
            "android.permission.health.READ_HEART_RATE",
            "android.permission.health.WRITE_HEART_RATE",
            "android.permission.health.WRITE_RESTING_HEART_RATE",
            "android.permission.health.READ_HEART_RATE_VARIABILITY",
            "android.permission.health.WRITE_HEART_RATE_VARIABILITY",
        )

    private lateinit var client: HealthConnectClient

    // Captured once per class to avoid midnight boundary issues across test methods.
    private val today: LocalDate = LocalDate.now(ZoneOffset.UTC)

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            "Health Connect SDK not available — test skipped",
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE,
        )
        client = HealthConnectClient.getOrCreate(context)
    }

    // AC4 + AC9: RHR record count per period matches deterministic expectation
    @Test
    fun days5_rhrRecordCount_matchesExpected() =
        runBlocking {
            client.seedNocturnalData(SeedPeriod.DAYS_5, today = today)
            val records = client.readRhrRecords(SeedPeriod.DAYS_5)
            assertEquals(SeedConstants.expectedRhrCount(SeedPeriod.DAYS_5), records.size)
        }

    // AC1: Minimum RHR across seeded window equals hardcoded nadir constant
    @Test
    fun days5_minRhr_equalsNadirConstant() =
        runBlocking {
            client.seedNocturnalData(SeedPeriod.DAYS_5, today = today)
            val records = client.readRhrRecords(SeedPeriod.DAYS_5)
            assertEquals(SeedConstants.RHR_NADIR_BPM, records.minOf { it.beatsPerMinute })
        }

    // AC2: Nadir records (min bpm) fall within [02:30, 03:30] UTC
    @Test
    fun days5_nadirRecords_withinNadirWindow() =
        runBlocking {
            client.seedNocturnalData(SeedPeriod.DAYS_5, today = today)
            val records = client.readRhrRecords(SeedPeriod.DAYS_5)
            val minBpm = records.minOf { it.beatsPerMinute }
            val nadirRecords = records.filter { it.beatsPerMinute == minBpm }
            nadirRecords.forEach { rec ->
                val zdt = rec.time.atZone(ZoneOffset.UTC)
                val minutesFromMidnight = zdt.hour * 60 + zdt.minute
                assertTrue(
                    "Nadir record at ${rec.time} is outside [02:30, 03:30] UTC window",
                    minutesFromMidnight in 150..210, // 150 = 02:30, 210 = 03:30
                )
            }
        }

    // AC3: Minimum HRV RMSSD equals hardcoded minimum constant
    @Test
    fun days5_minHrv_equalsMinConstant() =
        runBlocking {
            client.seedNocturnalData(SeedPeriod.DAYS_5, today = today)
            val records = client.readHrvRecords(SeedPeriod.DAYS_5)
            assertEquals(
                SeedConstants.HRV_RMSSD_MIN_MS,
                records.minOf { it.heartRateVariabilityMillis },
                0.001,
            )
        }

    // AC5: HRV record count per period matches deterministic expectation
    @Test
    fun days5_hrvRecordCount_matchesExpected() =
        runBlocking {
            client.seedNocturnalData(SeedPeriod.DAYS_5, today = today)
            val records = client.readHrvRecords(SeedPeriod.DAYS_5)
            assertEquals(SeedConstants.expectedHrvCount(SeedPeriod.DAYS_5), records.size)
        }

    // AC6: Mean sleep duration across a period ≈ avgSleepHours (pure math, no HC needed)
    @Test
    fun meanSleepDuration_equalsAvgSleepHours() {
        assertEquals(
            SeedConstants.DEFAULT_AVG_SLEEP_HOURS,
            SeedConstants.meanSleepDuration(SeedPeriod.DAYS_5),
            0.01,
        )
        assertEquals(
            SeedConstants.DEFAULT_AVG_SLEEP_HOURS,
            SeedConstants.meanSleepDuration(SeedPeriod.DAYS_42),
            0.01,
        )
    }

    // AC7: Idempotency — seeding twice yields identical RHR record count
    @Test
    fun seedTwice_rhrCount_isIdempotent() =
        runBlocking {
            client.seedNocturnalData(SeedPeriod.DAYS_5, today = today)
            val firstCount = client.readRhrRecords(SeedPeriod.DAYS_5).size
            client.seedNocturnalData(SeedPeriod.DAYS_5, today = today)
            val secondCount = client.readRhrRecords(SeedPeriod.DAYS_5).size
            assertEquals(firstCount, secondCount)
        }

    // AC10: Every RHR record falls within the computed sleep window for its night
    @Test
    fun days5_allRhrRecords_withinSleepWindow() =
        runBlocking {
            client.seedNocturnalData(SeedPeriod.DAYS_5, today = today)
            val records = client.readRhrRecords(SeedPeriod.DAYS_5)
            for (dayIndex in 0 until SeedPeriod.DAYS_5.days) {
                val sleepStart = SeedConstants.sleepStartForDay(dayIndex, today)
                val sleepDurationHours = SeedConstants.sleepDurationForDay(dayIndex)
                val sleepEnd = sleepStart.plusSeconds((sleepDurationHours * 3600).toLong())
                val dayCount = records.count { it.time >= sleepStart && it.time < sleepEnd }
                assertTrue(
                    "Day $dayIndex: expected ≥${SeedConstants.RHR_PER_HOUR} records, got $dayCount",
                    dayCount >= SeedConstants.RHR_PER_HOUR,
                )
            }
        }

    // AC8: DAYS_14 seeds 14 complete nights
    @Test
    fun days14_rhrRecordCount_matchesExpected() =
        runBlocking {
            client.seedNocturnalData(SeedPeriod.DAYS_14, today = today)
            val records = client.readRhrRecords(SeedPeriod.DAYS_14)
            assertEquals(SeedConstants.expectedRhrCount(SeedPeriod.DAYS_14), records.size)
        }

    // Smoke: DAYS_42 full seed completes and both record types reach expected counts
    @Test
    fun days42_fullSeed_rhrAndHrvCountsMatch() =
        runBlocking {
            client.seedNocturnalData(SeedPeriod.DAYS_42, today = today)
            val rhrCount = client.readRhrRecords(SeedPeriod.DAYS_42).size
            val hrvCount = client.readHrvRecords(SeedPeriod.DAYS_42).size
            assertEquals(SeedConstants.expectedRhrCount(SeedPeriod.DAYS_42), rhrCount)
            assertEquals(SeedConstants.expectedHrvCount(SeedPeriod.DAYS_42), hrvCount)
        }

    // --- Read helpers (paginated) ---

    private suspend fun HealthConnectClient.readRhrRecords(period: SeedPeriod): List<RestingHeartRateRecord> =
        readAllRecords(
            recordType = RestingHeartRateRecord::class,
            start = SeedConstants.sleepStartForDay(period.days - 1, today),
            end = Instant.now(),
        )

    private suspend fun HealthConnectClient.readHrvRecords(period: SeedPeriod): List<HeartRateVariabilityRmssdRecord> =
        readAllRecords(
            recordType = HeartRateVariabilityRmssdRecord::class,
            start = SeedConstants.sleepStartForDay(period.days - 1, today),
            end = Instant.now(),
        )

    private suspend fun <T : Record> HealthConnectClient.readAllRecords(
        recordType: KClass<T>,
        start: Instant,
        end: Instant,
    ): List<T> {
        val all = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response =
                readRecords(
                    ReadRecordsRequest(
                        recordType = recordType,
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                        pageToken = pageToken,
                    ),
                )
            all += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        return all
    }

    // --- Assertion helpers ---

    private fun assertEquals(
        expected: Long,
        actual: Long,
    ) = org.junit.Assert.assertEquals(expected, actual)

    private fun assertEquals(
        expected: Int,
        actual: Int,
    ) = org.junit.Assert.assertEquals(expected, actual)

    private fun assertEquals(
        expected: Double,
        actual: Double,
        delta: Double,
    ) = org.junit.Assert.assertEquals(expected, actual, delta)

    private fun assertTrue(
        message: String,
        condition: Boolean,
    ) = org.junit.Assert.assertTrue(message, condition)
}
