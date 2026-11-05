package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.records.Record
import app.readylytics.health.core.model.di.IoDispatcher
import app.readylytics.health.core.model.domain.repository.HealthConnectPermissionRevokedException
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.model.domain.util.logE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

/**
 * Wraps a Health Connect read in the availability -> permission -> exception-translation chain,
 * mapping each failure to the matching [ReadOutcome].
 */
@Singleton
class HealthConnectReadGuard
    @Inject
    constructor(
        private val availability: HealthConnectAvailability,
        private val permissions: HealthConnectPermissionGateway,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /**
         * Runs [block] on the IO dispatcher. Unavailable SDK -> [ReadOutcome.Unsupported]; missing
         * read permission or any permission-revoked/security failure -> [ReadOutcome.Denied];
         * any other failure is rethrown (logged first iff [logUnexpected]).
         */
        suspend fun <R> guardedRead(
            recordType: KClass<out Record>,
            label: String,
            logUnexpected: Boolean = true,
            block: suspend () -> R,
        ): ReadOutcome<R> =
            withContext(ioDispatcher) {
                if (!availability.isAvailable()) return@withContext ReadOutcome.Unsupported
                if (!permissions.hasReadPermission(recordType, label)) return@withContext ReadOutcome.Denied
                try {
                    ReadOutcome.Available(block())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HealthConnectPermissionRevokedException) {
                    logDenied(label, e)
                    ReadOutcome.Denied
                } catch (e: SecurityException) {
                    logDenied(label, e)
                    ReadOutcome.Denied
                } catch (e: Exception) {
                    if (e.asHealthConnectSecurityCause() != null) {
                        logDenied(label, e)
                        ReadOutcome.Denied
                    } else {
                        if (logUnexpected) {
                            logE("HealthConnectRepository", e) { "Error reading $label records" }
                        }
                        throw e
                    }
                }
            }

        private fun logDenied(
            label: String,
            e: Exception,
        ) {
            logD("HealthConnectRepository") { "$label record permission not granted: ${e.message}" }
        }
    }
