package app.readylytics.health.core.healthconnect.data.healthconnect

import app.readylytics.health.core.healthconnect.data.mapper.MapperHelpers
import app.readylytics.health.core.model.domain.model.DomainBloodPressureRecord
import app.readylytics.health.core.model.domain.model.DomainBodyFatRecord
import app.readylytics.health.core.model.domain.model.DomainBodyTemperatureRecord
import app.readylytics.health.core.model.domain.model.DomainOxygenSaturationRecord
import app.readylytics.health.core.model.domain.model.DomainWeightRecord
import app.readylytics.health.core.model.domain.sync.BloodPressureInput
import app.readylytics.health.core.model.domain.sync.BodyFatInput
import app.readylytics.health.core.model.domain.sync.BodyTemperatureInput
import app.readylytics.health.core.model.domain.sync.OxygenSaturationInput
import app.readylytics.health.core.model.domain.sync.WeightInput

/**
 * Vitals `Domain*Record` -> `*Input` mappers for the Health Connect Changes API path
 * ([HealthChangeSynchronizerImpl]). Split out of that file to keep it under the file-size
 * target (R2-ARCH-002) -- these mirror the equivalent inline mapping in
 * `HealthIngestionCoordinator` for the bulk/resync path.
 */
internal fun DomainWeightRecord.toWeightInput() = WeightInput(
    id = MapperHelpers.extractRecordIdFromInstant(id, time),
    timestampMs = MapperHelpers.extractTimestampMs(time),
    weightKg = weightKg,
    deviceName = MapperHelpers.extractDeviceName(deviceName),
)

internal fun DomainBodyFatRecord.toBodyFatInput() = BodyFatInput(
    id = MapperHelpers.extractRecordIdFromInstant(id, time),
    timestampMs = MapperHelpers.extractTimestampMs(time),
    bodyFatPercent = percentage,
    deviceName = MapperHelpers.extractDeviceName(deviceName),
)

internal fun DomainBloodPressureRecord.toBloodPressureInput() = BloodPressureInput(
    id = MapperHelpers.extractRecordIdFromInstant(id, time),
    timestampMs = MapperHelpers.extractTimestampMs(time),
    systolicMmHg = systolicMmHg,
    diastolicMmHg = diastolicMmHg,
    deviceName = MapperHelpers.extractDeviceName(deviceName),
)

internal fun DomainOxygenSaturationRecord.toOxygenSaturationInput() = OxygenSaturationInput(
    id = MapperHelpers.extractRecordIdFromInstant(id, time),
    timestampMs = MapperHelpers.extractTimestampMs(time),
    percentage = percentage,
    deviceName = MapperHelpers.extractDeviceName(deviceName),
)

internal fun DomainBodyTemperatureRecord.toBodyTemperatureInput() = BodyTemperatureInput(
    id = MapperHelpers.extractRecordIdFromInstant(id, time),
    timestampMs = MapperHelpers.extractTimestampMs(time),
    celsius = celsius,
    deviceName = MapperHelpers.extractDeviceName(deviceName),
)
