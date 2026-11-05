package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.SleepSessionRecord
import app.readylytics.health.core.model.domain.model.DomainHeartRateRecord
import app.readylytics.health.core.model.domain.model.DomainHrvRecord
import app.readylytics.health.core.model.domain.model.DomainSleepSessionRecord
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.repository.ReadRetryScope
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads Health Connect sleep, heart-rate and HRV records, in bulk or as a streaming page callback.
 */
@Singleton
class HealthConnectVitalsReader
    @Inject
    constructor(
        private val pagedReader: HealthConnectPagedReader,
        private val guard: HealthConnectReadGuard,
    ) {
        suspend fun readSleepSessions(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainSleepSessionRecord>> =
            guard.guardedRead(SleepSessionRecord::class, "Sleep session") {
                pagedReader.readAll<SleepSessionRecord>(from, to, retryScope).map { it.toDomain() }
            }

        suspend fun readHeartRateSamples(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainHeartRateRecord>> =
            guard.guardedRead(HeartRateRecord::class, "Heart rate") {
                pagedReader.readAll<HeartRateRecord>(from, to, retryScope).map { it.toDomain() }
            }

        suspend fun readHrvSamples(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainHrvRecord>> =
            guard.guardedRead(HeartRateVariabilityRmssdRecord::class, "HRV") {
                pagedReader.readAll<HeartRateVariabilityRmssdRecord>(from, to, retryScope).map { it.toDomain() }
            }

        suspend fun readHeartRateSamplesPaged(
            from: Instant,
            to: Instant,
            startPageToken: String?,
            retryScope: ReadRetryScope?,
            onPage: suspend (List<DomainHeartRateRecord>, String?) -> Unit,
        ): ReadOutcome<Unit> =
            guard.guardedRead(HeartRateRecord::class, "Heart rate", logUnexpected = false) {
                pagedReader.readStreaming<HeartRateRecord>(from, to, startPageToken, retryScope) { page, nextToken ->
                    onPage(page.map { it.toDomain() }, nextToken)
                }
            }

        suspend fun readHrvSamplesPaged(
            from: Instant,
            to: Instant,
            startPageToken: String?,
            retryScope: ReadRetryScope?,
            onPage: suspend (List<DomainHrvRecord>, String?) -> Unit,
        ): ReadOutcome<Unit> =
            guard.guardedRead(HeartRateVariabilityRmssdRecord::class, "HRV", logUnexpected = false) {
                pagedReader.readStreaming<HeartRateVariabilityRmssdRecord>(
                    from,
                    to,
                    startPageToken,
                    retryScope,
                ) { page, nextToken ->
                    onPage(page.map { it.toDomain() }, nextToken)
                }
            }
    }
