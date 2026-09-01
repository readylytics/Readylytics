package app.readylytics.health.core.healthconnect.data.healthconnect

import android.health.connect.HealthConnectException
import android.os.RemoteException
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BloodPressureRecord as HealthConnectBloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord as HealthConnectBodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord as HealthConnectHeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord as HealthConnectWeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.readylytics.health.core.model.domain.model.DomainIntervalTotal
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.sync.BloodPressureInput
import app.readylytics.health.core.model.domain.sync.BodyFatInput
import app.readylytics.health.core.model.domain.sync.BodyTemperatureInput
import app.readylytics.health.core.model.domain.sync.HealthIngestionBatch
import app.readylytics.health.core.model.domain.sync.OxygenSaturationInput
import app.readylytics.health.core.model.domain.sync.SleepSessionInput
import app.readylytics.health.core.model.domain.sync.SleepStageInput
import app.readylytics.health.core.model.domain.sync.StepRecordInput
import app.readylytics.health.core.model.domain.sync.WeightInput
import app.readylytics.health.core.model.domain.sync.WorkoutInput
import app.readylytics.health.core.model.domain.util.SessionTotalsResolver
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.model.domain.util.logW
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Stateless support functions for [HealthChangeSynchronizerImpl], split out to keep that file
 * under the file-size target (R2-ARCH-002). None of these need the synchronizer's injected
 * dependencies -- they operate purely on their parameters.
 */
internal fun recordClassesFor(dataType: HealthDataType): Set<kotlin.reflect.KClass<out Record>> =
    when (dataType) {
        HealthDataType.EXERCISE -> setOf(ExerciseSessionRecord::class)
        HealthDataType.STEPS -> setOf(StepsRecord::class)
        HealthDataType.BODY_FAT -> setOf(HealthConnectBodyFatRecord::class)
        HealthDataType.WEIGHT -> setOf(HealthConnectWeightRecord::class)
        HealthDataType.SLEEP -> setOf(SleepSessionRecord::class)
        HealthDataType.BLOOD_PRESSURE -> setOf(HealthConnectBloodPressureRecord::class)
        HealthDataType.HEART_RATE -> setOf(HealthConnectHeartRateRecord::class)
        HealthDataType.HRV -> setOf(HeartRateVariabilityRmssdRecord::class)
        HealthDataType.OXYGEN_SATURATION -> setOf(OxygenSaturationRecord::class)
        HealthDataType.BODY_TEMPERATURE -> setOf(BodyTemperatureRecord::class)
    }

internal fun isTokenExpiredException(e: Exception): Boolean {
    val signal = expiredTokenSignal(e)
    if (signal == ExpiredTokenSignal.REMOTE_FALLBACK) {
        logW("HealthChangeSyncSupport") {
            "Inferring expired Health Connect change token from RemoteException"
        }
    }
    return signal != ExpiredTokenSignal.NONE
}

private enum class ExpiredTokenSignal { NONE, HEALTH_CONNECT, REMOTE_FALLBACK }

private fun expiredTokenSignal(throwable: Throwable?): ExpiredTokenSignal {
    // The Health Connect client maps ErrorCode.CHANGES_TOKEN_OUTDATED to a RemoteException, and the
    // platform-integrated layer can wrap it in HealthConnectException with ERROR_REMOTE. Detect the
    // expired-token signal by concrete type rather than by scanning message text (which was brittle
    // and locale-dependent). A bare RemoteException is the imprecise fallback (it can also mean a
    // genuine service failure), so warn when only that path fires; a full resync is safe and
    // idempotent either way.
    var result: ExpiredTokenSignal? = null
    var current: Throwable? = throwable
    var depth = 0
    while (result == null && current != null && depth < MAX_CAUSE_DEPTH) {
        when {
            current is HealthConnectException -> {
                result =
                    if (current.errorCode == HealthConnectException.ERROR_REMOTE) {
                        ExpiredTokenSignal.HEALTH_CONNECT
                    } else {
                        ExpiredTokenSignal.NONE
                    }
            }
            current is RemoteException -> result = ExpiredTokenSignal.REMOTE_FALLBACK
            else -> {
                current = current.cause
                depth++
            }
        }
    }
    return result ?: ExpiredTokenSignal.NONE
}

private const val MAX_CAUSE_DEPTH = 10

internal fun getDatesBetween(
    start: Instant,
    end: Instant,
    zoneId: ZoneId,
): Set<LocalDate> {
    val startDate = start.atZone(zoneId).toLocalDate()
    val endDate = end.atZone(zoneId).toLocalDate()
    val dates = mutableSetOf<LocalDate>()
    var current = startDate
    while (!current.isAfter(endDate)) {
        dates.add(current)
        current = current.plusDays(1)
    }
    return dates
}

internal fun getDateFor(
    time: Instant,
    zoneId: ZoneId,
): Set<LocalDate> = setOf(time.atZone(zoneId).toLocalDate())

internal fun getDatesForRecord(
    record: Record,
    zoneId: ZoneId,
): Set<LocalDate> =
    when (record) {
        is SleepSessionRecord -> getDatesBetween(record.startTime, record.endTime, zoneId)
        is ExerciseSessionRecord -> getDatesBetween(record.startTime, record.endTime, zoneId)
        is StepsRecord -> getDatesBetween(record.startTime, record.endTime, zoneId)
        is HealthConnectHeartRateRecord -> getDatesBetween(record.startTime, record.endTime, zoneId)
        is HeartRateVariabilityRmssdRecord -> getDateFor(record.time, zoneId)
        is HealthConnectWeightRecord -> getDateFor(record.time, zoneId)
        is HealthConnectBodyFatRecord -> getDateFor(record.time, zoneId)
        is HealthConnectBloodPressureRecord -> getDateFor(record.time, zoneId)
        is OxygenSaturationRecord -> getDateFor(record.time, zoneId)
        is BodyTemperatureRecord -> getDateFor(record.time, zoneId)
        else -> emptySet()
    }

internal fun emptyBatch(
    sleepSessions: List<SleepSessionInput> = emptyList(),
    sleepStages: List<SleepStageInput> = emptyList(),
    workouts: List<WorkoutInput> = emptyList(),
    weights: List<WeightInput> = emptyList(),
    bodyFatSamples: List<BodyFatInput> = emptyList(),
    bloodPressureSamples: List<BloodPressureInput> = emptyList(),
    oxygenSaturationSamples: List<OxygenSaturationInput> = emptyList(),
    bodyTemperatureSamples: List<BodyTemperatureInput> = emptyList(),
    stepRecords: List<StepRecordInput> = emptyList(),
) = HealthIngestionBatch(
    sleepSessions = sleepSessions, sleepStages = sleepStages, heartRateSamples = emptyList(),
    hrvSamples = emptyList(), workouts = workouts, weights = weights, bodyFatSamples = bodyFatSamples,
    bloodPressureSamples = bloodPressureSamples, oxygenSaturationSamples = oxygenSaturationSamples,
    bodyTemperatureSamples = bodyTemperatureSamples, stepRecords = stepRecords,
)

/**
 * Same-package attribution of one optional interval record type (distance, elevation) to a
 * delta-synced exercise session -- the per-session equivalent of the bulk
 * `readIntervalTotals` + `SessionTotalsResolver` pass in `HealthConnectRepositoryImpl`.
 * Returns null when the optional permission is missing: enrichment, never a sync failure.
 */
internal suspend inline fun <reified T : Record> sessionTotalFor(
    client: HealthConnectClient,
    session: ExerciseSessionRecord,
    map: (T) -> DomainIntervalTotal,
): Double? =
    try {
        val totals = mutableListOf<DomainIntervalTotal>()
        var pageToken: String? = null
        do {
            val response =
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = T::class,
                        timeRangeFilter = TimeRangeFilter.between(session.startTime, session.endTime),
                        pageToken = pageToken,
                    ),
                )
            totals += response.records.map(map)
            pageToken = response.pageToken
        } while (pageToken != null)
        SessionTotalsResolver.totalFor(
            sessionStart = session.startTime,
            sessionEnd = session.endTime,
            sessionOrigin = session.metadata.dataOrigin.packageName,
            totals = totals,
        )
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e.asHealthConnectSecurityCause() == null) throw e
        logD("HealthChangeSynchronizer") {
            "${T::class.simpleName} permission not granted; session stored without ${T::class.simpleName} total"
        }
        null
    }
