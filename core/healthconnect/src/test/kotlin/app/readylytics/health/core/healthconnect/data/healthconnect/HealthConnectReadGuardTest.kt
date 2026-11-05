package app.readylytics.health.core.healthconnect.data.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.permission.HealthPermission
import app.readylytics.health.core.model.domain.repository.HealthConnectPermissionRevokedException
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.repository.WorkoutRouteLookup
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Characterization pins for the Health Connect read-translation guard chain (availability ->
 * permission -> SDK read -> exception translation), driven through the repository facade.
 */
class HealthConnectReadGuardTest {
    private val context = mockk<Context>(relaxed = true)
    private val client = mockk<HealthConnectClient>(relaxed = true)
    private val routeLookup = mockk<WorkoutRouteLookup>(relaxed = true)
    private val from = Instant.parse("2026-08-20T00:00:00Z")
    private val to = from.plusSeconds(3600)
    private lateinit var repo: HealthConnectRepositoryImpl

    @Before
    fun setup() {
        mockkObject(HealthConnectClient)
        every { HealthConnectClient.getSdkStatus(any()) } returns HealthConnectClient.SDK_AVAILABLE
        repo = createHealthConnectRepositoryForTest(context = context, client = client, routeLookup = routeLookup)
        coEvery { client.permissionController.getGrantedPermissions() } returns repo.allPermissions
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    private fun failReadsWith(failure: Throwable) {
        coEvery { client.readRecords<Record>(any()) } throws failure
    }

    private suspend fun readWeight() = repo.readWeightRecords(from, to, null)

    private suspend fun readHeartRatePaged() = repo.readHeartRateSamplesPaged(from, to, null, null) { _, _ -> }

    @Test
    fun sdkUnavailable_returnsUnsupported_withoutTouchingClient() =
        runTest {
            every { HealthConnectClient.getSdkStatus(any()) } returns HealthConnectClient.SDK_UNAVAILABLE

            assertEquals(ReadOutcome.Unsupported, readWeight())
            coVerify(exactly = 0) { client.readRecords<Record>(any()) }
            coVerify(exactly = 0) { client.permissionController.getGrantedPermissions() }
        }

    @Test
    fun missingPermission_returnsDenied_withoutReadRecords() =
        runTest {
            coEvery { client.permissionController.getGrantedPermissions() } returns
                repo.allPermissions - HealthPermission.getReadPermission(WeightRecord::class)

            assertEquals(ReadOutcome.Denied, readWeight())
            coVerify(exactly = 0) { client.readRecords<Record>(any()) }
        }

    @Test
    fun securityException_returnsDenied() =
        runTest {
            failReadsWith(SecurityException("revoked"))

            assertEquals(ReadOutcome.Denied, readWeight())
        }

    @Test
    fun healthConnectPermissionRevokedException_returnsDenied() =
        runTest {
            failReadsWith(HealthConnectPermissionRevokedException(SecurityException("revoked")))

            assertEquals(ReadOutcome.Denied, readWeight())
        }

    @Test
    fun wrappedSecurityCause_returnsDenied() =
        runTest {
            failReadsWith(RuntimeException(SecurityException("revoked")))

            assertEquals(ReadOutcome.Denied, readWeight())
        }

    @Test
    fun otherException_isRethrown() =
        runTest {
            val failure = IllegalStateException("provider failed")
            failReadsWith(failure)

            assertSame(failure, assertFailsWith<IllegalStateException> { readWeight() })
        }

    @Test
    fun cancellationException_propagates_notDenied() =
        runTest {
            val cancellation = CancellationException("cancelled")
            failReadsWith(cancellation)

            assertSame(cancellation, assertFailsWith<CancellationException> { readWeight() })
        }

    @Test
    fun readHeartRateSamplesPaged_securityException_returnsDenied() =
        runTest {
            failReadsWith(SecurityException("revoked"))

            assertEquals(ReadOutcome.Denied, readHeartRatePaged())
        }

    @Test
    fun readHeartRateSamplesPaged_otherException_isRethrown() =
        runTest {
            val failure = IllegalStateException("provider failed")
            failReadsWith(failure)

            assertSame(failure, assertFailsWith<IllegalStateException> { readHeartRatePaged() })
        }

    @Test
    fun getGrantedPermissionsCancellation_propagatesFromRead() =
        runTest {
            coEvery { client.permissionController.getGrantedPermissions() } throws CancellationException("cancelled")

            // Coroutine stack-trace recovery may copy the exception, so assert type + message, not identity.
            assertEquals("cancelled", assertFailsWith<CancellationException> { readWeight() }.message)
        }

    @Test
    fun getGrantedPermissionsCancellation_propagatesFromCheckPermissions() =
        runTest {
            coEvery { client.permissionController.getGrantedPermissions() } throws CancellationException("cancelled")

            assertEquals("cancelled", assertFailsWith<CancellationException> { repo.checkPermissions() }.message)
        }
}
