package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.readylytics.health.core.healthconnect.domain.sync.retryWithBackoff
import app.readylytics.health.core.model.domain.repository.ReadRetryScope
import kotlinx.coroutines.CancellationException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

/**
 * Reads Health Connect records page by page. Each page read is retried via [retryWithBackoff]
 * (R2-HC-002) and security failures are translated by [rethrowReadFailureOrOriginal].
 */
@Singleton
class HealthConnectPagedReader
    @Inject
    constructor(
        private val client: HealthConnectClient,
    ) {
        suspend fun <T : Record> readAll(
            recordType: KClass<T>,
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope? = null,
        ): List<T> {
            val all = mutableListOf<T>()
            readStreaming(recordType, from, to, retryScope = retryScope) { page, _ -> all.addAll(page) }
            return all
        }

        /**
         * Paged variant of [readAll]: invokes [onPage] once per Health Connect page instead of
         * accumulating every page into one list, so a dense window never holds more than one page
         * of raw HC records in memory at once (HC-001).
         */
        suspend fun <T : Record> readStreaming(
            recordType: KClass<T>,
            from: Instant,
            to: Instant,
            startPageToken: String? = null,
            retryScope: ReadRetryScope? = null,
            onPage: suspend (records: List<T>, nextPageToken: String?) -> Unit,
        ) {
            var pageToken: String? = startPageToken
            try {
                do {
                    val currentToken = pageToken
                    val response =
                        retryWithBackoff(budget = retryScope) {
                            client.readRecords(
                                ReadRecordsRequest(
                                    recordType = recordType,
                                    timeRangeFilter = TimeRangeFilter.between(from, to),
                                    pageToken = currentToken,
                                ),
                            )
                        }
                    pageToken = response.pageToken
                    onPage(response.records, pageToken)
                } while (pageToken != null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                rethrowReadFailureOrOriginal(recordType.simpleName, e)
            }
        }
    }

internal suspend inline fun <reified T : Record> HealthConnectPagedReader.readAll(
    from: Instant,
    to: Instant,
    retryScope: ReadRetryScope? = null,
): List<T> = readAll(T::class, from, to, retryScope)

internal suspend inline fun <reified T : Record> HealthConnectPagedReader.readStreaming(
    from: Instant,
    to: Instant,
    startPageToken: String? = null,
    retryScope: ReadRetryScope? = null,
    noinline onPage: suspend (records: List<T>, nextPageToken: String?) -> Unit,
) = readStreaming(T::class, from, to, startPageToken, retryScope, onPage)
