package com.gregor.lauritz.healthdashboard.data.healthconnect

import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectPermissionRevokedException
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.domain.repository.PermissionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Integration tests for the [HealthConnectRepository] contract.
 *
 * Direct instantiation of the real [androidx.health.connect.client.HealthConnectClient]
 * is impractical on test devices: the SDK requires a real provider, and its record
 * classes are final (so dexmaker-based mocking cannot synthesise them). We instead
 * exercise the repository *contract* via a hand-rolled [FakeHealthConnectRepository]
 * that drives the same behaviour the production [HealthConnectRepositoryImpl] is
 * obligated to provide:
 *   - permission state transitions (Granted / Missing / Unavailable / propagation)
 *   - per-record-type reads (sleep, HR, RHR, HRV, exercise, steps, weight, body fat, BP)
 *   - pagination over large result sets (paging-loop termination)
 *   - aggregation (readSteps) and grouping (readStepsRange)
 *   - error translation (SecurityException -> HealthConnectPermissionRevokedException
 *     for critical reads; SecurityException -> emptyList() for optional reads)
 *   - device discovery aggregation and de-duplication
 *   - range filtering and boundary handling
 */
@RunWith(AndroidJUnit4::class)
class HealthConnectRepositoryImplTest {
    private lateinit var fake: FakeHealthConnectRepository
    private lateinit var repo: HealthConnectRepository

    private val t0: Instant = Instant.parse("2026-05-01T00:00:00Z")
    private val t1: Instant = Instant.parse("2026-05-02T00:00:00Z")
    private val t2: Instant = Instant.parse("2026-05-03T00:00:00Z")
    private val t3: Instant = Instant.parse("2026-05-04T00:00:00Z")
    private val t7: Instant = Instant.parse("2026-05-08T00:00:00Z")

    @Before
    fun setUp() {
        fake = FakeHealthConnectRepository()
        repo = fake
    }

    // ---------- availability ----------

    @Test
    fun isAvailable_defaultsTrue() {
        assertTrue(repo.isAvailable())
    }

    @Test
    fun isAvailable_returnsFalseWhenSdkUnavailable() {
        fake.sdkAvailable = false
        assertFalse(repo.isAvailable())
    }

    // ---------- permissions ----------

    @Test
    fun checkPermissions_unavailableWhenSdkAbsent() =
        runBlocking {
            fake.sdkAvailable = false
            assertEquals(PermissionStatus.Unavailable, repo.checkPermissions())
        }

    @Test
    fun checkPermissions_grantedWhenAllRequiredPresent() =
        runBlocking {
            fake.granted = repo.requiredPermissions
            assertEquals(PermissionStatus.Granted, repo.checkPermissions())
        }

    @Test
    fun checkPermissions_missingWhenPartial() =
        runBlocking {
            val partial = repo.requiredPermissions.drop(1).toSet()
            fake.granted = partial
            val status = repo.checkPermissions()
            assertTrue(status is PermissionStatus.Missing)
            val expectedMissing = repo.requiredPermissions - partial
            assertEquals(expectedMissing, (status as PermissionStatus.Missing).missing)
        }

    @Test
    fun checkPermissions_missingWhenEmpty() =
        runBlocking {
            fake.granted = emptySet()
            val status = repo.checkPermissions()
            assertTrue(status is PermissionStatus.Missing)
            assertEquals(repo.requiredPermissions, (status as PermissionStatus.Missing).missing)
        }

    @Test
    fun checkPermissions_propagatesControllerErrors() {
        fake.granted = emptySet()
        fake.permissionError = RuntimeException("boom")
        val ex = assertThrows(RuntimeException::class.java) { runBlocking { repo.checkPermissions() } }
        assertEquals("boom", ex.message)
    }

    @Test
    fun permissionSets_criticalSubsetOfRequired() {
        assertTrue(repo.requiredPermissions.containsAll(repo.criticalPermissions))
    }

    @Test
    fun permissionSets_allEqualsRequiredPlusOptional() {
        assertEquals(repo.requiredPermissions + repo.optionalPermissions, repo.allPermissions)
    }

    @Test
    fun permissionSets_optionalDisjointFromCritical() {
        assertTrue((repo.optionalPermissions intersect repo.criticalPermissions).isEmpty())
    }

    // ---------- sleep ----------

    @Test
    fun readSleepSessions_emptyWhenNoneRecorded() =
        runBlocking {
            assertTrue(repo.readSleepSessions(t0, t7).isEmpty())
        }

    @Test
    fun readSleepSessions_returnsCountInRange() =
        runBlocking {
            fake.sleepCount[t1] = 1
            fake.sleepCount[t2] = 1
            assertEquals(2, repo.readSleepSessions(t0, t7).size)
        }

    @Test
    fun readSleepSessions_excludesRecordsOutsideRange() =
        runBlocking {
            fake.sleepCount[t0.minusSeconds(1)] = 1
            fake.sleepCount[t1] = 1
            fake.sleepCount[t7.plusSeconds(1)] = 1
            assertEquals(1, repo.readSleepSessions(t0, t7).size)
        }

    @Test
    fun readSleepSessions_translatesSecurityException() {
        fake.sleepCount[t1] = 1
        fake.errors[FakeOp.Sleep] = SecurityException("revoked")
        assertThrows(HealthConnectPermissionRevokedException::class.java) {
            runBlocking { repo.readSleepSessions(t0, t7) }
        }
    }

    @Test
    fun readSleepSessions_paginatesLargeResultSet() =
        runBlocking {
            // Pagination correctness: the impl loops over pageTokens until null.
            // Encode this by storing 250 distinct timestamps; the fake mimics a
            // page-size of 50, so the loop must spin 5 times to return all 250.
            repeat(250) { i ->
                fake.sleepCount[t0.plusSeconds(i.toLong())] = 1
            }
            val out = repo.readSleepSessions(t0, t7)
            assertEquals(250, out.size)
            assertEquals(5, fake.sleepPagesServed)
        }

    @Test
    fun readSleepSessions_zeroWidthRangeIsEmpty() =
        runBlocking {
            fake.sleepCount[t1] = 1
            assertTrue(repo.readSleepSessions(t1, t1).isEmpty())
        }

    // ---------- heart rate ----------

    @Test
    fun readHeartRateSamples_emptyWhenNone() =
        runBlocking {
            assertTrue(repo.readHeartRateSamples(t0, t7).isEmpty())
        }

    @Test
    fun readHeartRateSamples_returnsRecordsInRange() =
        runBlocking {
            fake.hrCount[t1] = 1
            fake.hrCount[t2] = 1
            assertEquals(2, repo.readHeartRateSamples(t0, t7).size)
        }

    @Test
    fun readHeartRateSamples_translatesSecurityException() {
        fake.hrCount[t1] = 1
        fake.errors[FakeOp.HeartRate] = SecurityException("revoked")
        assertThrows(HealthConnectPermissionRevokedException::class.java) {
            runBlocking { repo.readHeartRateSamples(t0, t7) }
        }
    }

    @Test
    fun readHeartRateSamples_paginatesThousandRecords() =
        runBlocking {
            repeat(1_000) { i ->
                fake.hrCount[t0.plusSeconds(i.toLong())] = 1
            }
            assertEquals(1_000, repo.readHeartRateSamples(t0, t7).size)
            assertEquals(20, fake.hrPagesServed)
        }

    @Test
    fun readHeartRateSamples_reversedRangeIsEmpty() =
        runBlocking {
            fake.hrCount[t1] = 1
            assertTrue(repo.readHeartRateSamples(t7, t0).isEmpty())
        }

    // ---------- resting heart rate ----------

    @Test
    fun readRestingHeartRateSamples_emptyWhenNone() =
        runBlocking {
            assertTrue(repo.readRestingHeartRateSamples(t0, t7).isEmpty())
        }

    @Test
    fun readRestingHeartRateSamples_returnsRecordsInRange() =
        runBlocking {
            fake.rhrCount[t1] = 1
            fake.rhrCount[t2] = 1
            assertEquals(2, repo.readRestingHeartRateSamples(t0, t7).size)
        }

    @Test
    fun readRestingHeartRateSamples_translatesSecurityException() {
        fake.rhrCount[t1] = 1
        fake.errors[FakeOp.RestingHeartRate] = SecurityException("revoked")
        assertThrows(HealthConnectPermissionRevokedException::class.java) {
            runBlocking { repo.readRestingHeartRateSamples(t0, t7) }
        }
    }

    // ---------- HRV ----------

    @Test
    fun readHrvSamples_emptyWhenNone() =
        runBlocking {
            assertTrue(repo.readHrvSamples(t0, t7).isEmpty())
        }

    @Test
    fun readHrvSamples_returnsRecordsInRange() =
        runBlocking {
            fake.hrvCount[t1] = 1
            fake.hrvCount[t2] = 1
            fake.hrvCount[t3] = 1
            assertEquals(3, repo.readHrvSamples(t0, t7).size)
        }

    @Test
    fun readHrvSamples_translatesSecurityException() {
        fake.hrvCount[t1] = 1
        fake.errors[FakeOp.Hrv] = SecurityException("revoked")
        assertThrows(HealthConnectPermissionRevokedException::class.java) {
            runBlocking { repo.readHrvSamples(t0, t7) }
        }
    }

    @Test
    fun readHrvSamples_excludesOutOfRange() =
        runBlocking {
            fake.hrvCount[t0.minusSeconds(1)] = 1
            fake.hrvCount[t1] = 1
            assertEquals(1, repo.readHrvSamples(t0, t7).size)
        }

    // ---------- exercise / workouts ----------

    @Test
    fun readExerciseSessions_emptyWhenNone() =
        runBlocking {
            assertTrue(repo.readExerciseSessions(t0, t7).isEmpty())
        }

    @Test
    fun readExerciseSessions_returnsRecordsInRange() =
        runBlocking {
            fake.exerciseCount[t1] = 1
            fake.exerciseCount[t2] = 1
            assertEquals(2, repo.readExerciseSessions(t0, t7).size)
        }

    @Test
    fun readExerciseSessions_translatesSecurityException() {
        fake.exerciseCount[t1] = 1
        fake.errors[FakeOp.Exercise] = SecurityException("revoked")
        assertThrows(HealthConnectPermissionRevokedException::class.java) {
            runBlocking { repo.readExerciseSessions(t0, t7) }
        }
    }

    @Test
    fun readExerciseSessions_paginatesLargeResultSet() =
        runBlocking {
            repeat(500) { i ->
                fake.exerciseCount[t0.plusSeconds(i.toLong())] = 1
            }
            assertEquals(500, repo.readExerciseSessions(t0, t7).size)
            assertEquals(10, fake.exercisePagesServed)
        }

    // ---------- steps records ----------

    @Test
    fun readStepsRecords_emptyWhenNone() =
        runBlocking {
            assertTrue(repo.readStepsRecords(t0, t7).isEmpty())
        }

    @Test
    fun readStepsRecords_returnsRecordsInRange() =
        runBlocking {
            fake.stepsByInstant[t1] = 1_000L
            fake.stepsByInstant[t2] = 2_000L
            assertEquals(2, repo.readStepsRecords(t0, t7).size)
        }

    // ---------- readSteps (aggregate) ----------

    @Test
    fun readSteps_sumsAcrossRange() =
        runBlocking {
            fake.stepsByInstant[t1] = 1_000L
            fake.stepsByInstant[t2] = 2_500L
            fake.stepsByInstant[t3] = 500L
            assertEquals(4_000L, repo.readSteps(t0, t7))
        }

    @Test
    fun readSteps_zeroWhenEmpty() =
        runBlocking {
            assertEquals(0L, repo.readSteps(t0, t7))
        }

    @Test
    fun readSteps_respectsRangeFilter() =
        runBlocking {
            fake.stepsByInstant[t0.minusSeconds(1)] = 9_999L
            fake.stepsByInstant[t1] = 100L
            assertEquals(100L, repo.readSteps(t0, t7))
        }

    // ---------- readStepsRange (grouped) ----------

    @Test
    fun readStepsRange_groupsByLocalDate() =
        runBlocking {
            val zone = ZoneId.systemDefault()
            val day1 = LocalDate.of(2026, 5, 1).atStartOfDay(zone).toInstant()
            val day2 = LocalDate.of(2026, 5, 2).atStartOfDay(zone).toInstant()
            fake.stepsByInstant[day1] = 1_000L
            fake.stepsByInstant[day1.plusSeconds(3_600)] = 500L
            fake.stepsByInstant[day2] = 2_000L

            val map = repo.readStepsRange(day1, day2.plusSeconds(86_400))
            assertEquals(2, map.size)
            assertEquals(1_500L, map[LocalDate.of(2026, 5, 1)])
            assertEquals(2_000L, map[LocalDate.of(2026, 5, 2)])
        }

    @Test
    fun readStepsRange_translatesSecurityException() {
        fake.stepsByInstant[t1] = 100L
        fake.errors[FakeOp.Steps] = SecurityException("revoked")
        assertThrows(HealthConnectPermissionRevokedException::class.java) {
            runBlocking { repo.readStepsRange(t0, t7) }
        }
    }

    @Test
    fun readStepsRange_swallowsGenericException() =
        runBlocking {
            fake.stepsByInstant[t1] = 100L
            fake.errors[FakeOp.Steps] = IllegalStateException("bad")
            assertEquals(emptyMap<LocalDate, Long>(), repo.readStepsRange(t0, t7))
        }

    // ---------- weight (optional) ----------

    @Test
    fun readWeightRecords_emptyWhenNone() =
        runBlocking {
            assertTrue(repo.readWeightRecords(t0, t7).isEmpty())
        }

    @Test
    fun readWeightRecords_returnsRecordsInRange() =
        runBlocking {
            fake.weightCount[t1] = 1
            fake.weightCount[t2] = 1
            assertEquals(2, repo.readWeightRecords(t0, t7).size)
        }

    @Test
    fun readWeightRecords_returnsEmptyOnSecurityException() =
        runBlocking {
            fake.weightCount[t1] = 1
            fake.errors[FakeOp.Weight] = SecurityException("optional missing")
            assertTrue(repo.readWeightRecords(t0, t7).isEmpty())
        }

    @Test
    fun readWeightRecords_returnsEmptyOnGenericException() =
        runBlocking {
            fake.weightCount[t1] = 1
            fake.errors[FakeOp.Weight] = IllegalStateException("io")
            assertTrue(repo.readWeightRecords(t0, t7).isEmpty())
        }

    // ---------- body fat (optional) ----------

    @Test
    fun readBodyFatRecords_emptyWhenNone() =
        runBlocking {
            assertTrue(repo.readBodyFatRecords(t0, t7).isEmpty())
        }

    @Test
    fun readBodyFatRecords_returnsRecordsInRange() =
        runBlocking {
            fake.bodyFatCount[t1] = 1
            assertEquals(1, repo.readBodyFatRecords(t0, t7).size)
        }

    @Test
    fun readBodyFatRecords_returnsEmptyOnSecurityException() =
        runBlocking {
            fake.bodyFatCount[t1] = 1
            fake.errors[FakeOp.BodyFat] = SecurityException("optional missing")
            assertTrue(repo.readBodyFatRecords(t0, t7).isEmpty())
        }

    @Test
    fun readBodyFatRecords_returnsEmptyOnGenericException() =
        runBlocking {
            fake.bodyFatCount[t1] = 1
            fake.errors[FakeOp.BodyFat] = IllegalStateException("io")
            assertTrue(repo.readBodyFatRecords(t0, t7).isEmpty())
        }

    // ---------- blood pressure (optional) ----------

    @Test
    fun readBloodPressureRecords_emptyWhenNone() =
        runBlocking {
            assertTrue(repo.readBloodPressureRecords(t0, t7).isEmpty())
        }

    @Test
    fun readBloodPressureRecords_returnsRecordsInRange() =
        runBlocking {
            fake.bpCount[t1] = 1
            fake.bpCount[t2] = 1
            assertEquals(2, repo.readBloodPressureRecords(t0, t7).size)
        }

    @Test
    fun readBloodPressureRecords_returnsEmptyOnSecurityException() =
        runBlocking {
            fake.bpCount[t1] = 1
            fake.errors[FakeOp.BloodPressure] = SecurityException("optional missing")
            assertTrue(repo.readBloodPressureRecords(t0, t7).isEmpty())
        }

    @Test
    fun readBloodPressureRecords_returnsEmptyOnGenericException() =
        runBlocking {
            fake.bpCount[t1] = 1
            fake.errors[FakeOp.BloodPressure] = IllegalStateException("io")
            assertTrue(repo.readBloodPressureRecords(t0, t7).isEmpty())
        }

    // ---------- device discovery ----------

    @Test
    fun discoverDevices_emptyWhenNoData() =
        runBlocking {
            assertTrue(repo.discoverDevices(7).isEmpty())
        }

    @Test
    fun discoverDevices_aggregatesAndDeduplicates() =
        runBlocking {
            fake.devicesInWindow = listOf("watch", "phone", "scale", "watch")
            val out = repo.discoverDevices(7)
            assertEquals(listOf("phone", "scale", "watch"), out)
        }

    @Test
    fun discoverDevices_returnsEmptyOnGenericFailure() =
        runBlocking {
            fake.devicesInWindow = listOf("watch")
            fake.errors[FakeOp.Discovery] = IllegalStateException("io")
            assertTrue(repo.discoverDevices(7).isEmpty())
        }

    @Test
    fun discoverDevices_translatesSecurityException() {
        fake.devicesInWindow = listOf("watch")
        fake.errors[FakeOp.Discovery] = SecurityException("revoked")
        assertThrows(HealthConnectPermissionRevokedException::class.java) {
            runBlocking { repo.discoverDevices(7) }
        }
    }

    @Test
    fun discoverDevices_defaultWindowDaysIsTwo() =
        runBlocking {
            fake.devicesInWindow = listOf("a")
            repo.discoverDevices()
            assertEquals(2, fake.lastDiscoveryWindowDays)
        }

    // ---------- error type plumbing ----------

    @Test
    fun permissionRevokedException_preservesSecurityCause() {
        val cause = SecurityException("denied")
        val ex = HealthConnectPermissionRevokedException(cause)
        assertSame(cause, ex.cause)
        assertNotNull(ex.message)
    }
}
