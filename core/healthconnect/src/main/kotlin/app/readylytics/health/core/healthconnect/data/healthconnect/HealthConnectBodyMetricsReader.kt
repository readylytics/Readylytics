package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import app.readylytics.health.core.model.domain.model.DomainBloodPressureRecord
import app.readylytics.health.core.model.domain.model.DomainBodyFatRecord
import app.readylytics.health.core.model.domain.model.DomainBodyTemperatureRecord
import app.readylytics.health.core.model.domain.model.DomainOxygenSaturationRecord
import app.readylytics.health.core.model.domain.model.DomainVo2MaxRecord
import app.readylytics.health.core.model.domain.model.DomainWeightRecord
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.repository.ReadRetryScope
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads Health Connect body-metric records: weight, body fat, blood pressure, oxygen saturation,
 * body temperature and VO2 max.
 */
@Singleton
class HealthConnectBodyMetricsReader
    @Inject
    constructor(
        private val pagedReader: HealthConnectPagedReader,
        private val guard: HealthConnectReadGuard,
    ) {
        suspend fun readWeightRecords(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainWeightRecord>> =
            guard.guardedRead(WeightRecord::class, "Weight") {
                pagedReader.readAll<WeightRecord>(from, to, retryScope).map { it.toDomain() }
            }

        suspend fun readBodyFatRecords(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainBodyFatRecord>> =
            guard.guardedRead(BodyFatRecord::class, "Body fat") {
                pagedReader.readAll<BodyFatRecord>(from, to, retryScope).map { it.toDomain() }
            }

        suspend fun readBloodPressureRecords(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainBloodPressureRecord>> =
            guard.guardedRead(BloodPressureRecord::class, "Blood pressure") {
                pagedReader.readAll<BloodPressureRecord>(from, to, retryScope).map { it.toDomain() }
            }

        suspend fun readOxygenSaturationRecords(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainOxygenSaturationRecord>> =
            guard.guardedRead(OxygenSaturationRecord::class, "Oxygen saturation") {
                pagedReader.readAll<OxygenSaturationRecord>(from, to, retryScope).map { it.toDomain() }
            }

        suspend fun readBodyTemperatureRecords(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainBodyTemperatureRecord>> =
            guard.guardedRead(BodyTemperatureRecord::class, "Body temperature") {
                pagedReader.readAll<BodyTemperatureRecord>(from, to, retryScope).map { it.toDomain() }
            }

        suspend fun readVo2MaxRecords(
            startTime: Instant,
            endTime: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainVo2MaxRecord>> =
            guard.guardedRead(Vo2MaxRecord::class, "VO2 max") {
                pagedReader.readAll<Vo2MaxRecord>(startTime, endTime, retryScope).map { it.toDomain() }
            }
    }
