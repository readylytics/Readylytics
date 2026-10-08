package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.StepRecordInput
import app.readylytics.health.core.model.domain.sync.Vo2MaxInput

/**
 * The single-sample vitals/steps/VO2-max upsert branches of [upsertRecords], split out of
 * `HealthChangeSynchronizerImplHelpers.kt` purely to keep each file's top-level function count
 * under detekt's TooManyFunctions threshold -- no behavior difference from being in one file.
 */
internal suspend fun upsertWeights(records: List<Record>, healthIngestionStore: HealthIngestionStore) {
    val inputs = records.filterIsInstance<WeightRecord>().map { it.toDomain().toWeightInput() }
    healthIngestionStore.persist(emptyBatch(weights = inputs))
}

internal suspend fun upsertBodyFats(records: List<Record>, healthIngestionStore: HealthIngestionStore) {
    val inputs = records.filterIsInstance<BodyFatRecord>().map { it.toDomain().toBodyFatInput() }
    healthIngestionStore.persist(emptyBatch(bodyFatSamples = inputs))
}

internal suspend fun upsertBloodPressures(records: List<Record>, healthIngestionStore: HealthIngestionStore) {
    val inputs = records.filterIsInstance<BloodPressureRecord>().map { it.toDomain().toBloodPressureInput() }
    healthIngestionStore.persist(emptyBatch(bloodPressureSamples = inputs))
}

internal suspend fun upsertOxygenSaturations(records: List<Record>, healthIngestionStore: HealthIngestionStore) {
    val inputs = records.filterIsInstance<OxygenSaturationRecord>().map { it.toDomain().toOxygenSaturationInput() }
    healthIngestionStore.persist(emptyBatch(oxygenSaturationSamples = inputs))
}

internal suspend fun upsertBodyTemperatures(records: List<Record>, healthIngestionStore: HealthIngestionStore) {
    val inputs = records.filterIsInstance<BodyTemperatureRecord>().map { it.toDomain().toBodyTemperatureInput() }
    healthIngestionStore.persist(emptyBatch(bodyTemperatureSamples = inputs))
}

internal suspend fun upsertStepsBatch(records: List<Record>, healthIngestionStore: HealthIngestionStore) {
    val inputs =
        records.filterIsInstance<StepsRecord>().map { record ->
            StepRecordInput(
                id = record.metadata.id,
                startTime = record.startTime.toEpochMilli(),
                endTime = record.endTime.toEpochMilli(),
                count = record.count,
                deviceName = DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin),
            )
        }
    healthIngestionStore.persist(emptyBatch(stepRecords = inputs))
}

internal suspend fun upsertVo2Maxes(records: List<Record>, healthIngestionStore: HealthIngestionStore) {
    val inputs =
        records.filterIsInstance<Vo2MaxRecord>().map { record ->
            val domain = record.toDomain()
            Vo2MaxInput(
                id = domain.id,
                timestampMs = domain.time.toEpochMilli(),
                vo2Max = domain.vo2MillilitersPerMinuteKilogram.toFloat(),
                measurementMethod = domain.measurementMethod,
                deviceName = domain.deviceName,
            )
        }
    healthIngestionStore.persist(emptyBatch(vo2MaxSamples = inputs))
}
