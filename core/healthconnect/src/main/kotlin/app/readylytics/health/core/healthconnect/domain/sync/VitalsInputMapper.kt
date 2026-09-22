package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.dataOrEmpty
import app.readylytics.health.core.model.domain.sync.BloodPressureInput
import app.readylytics.health.core.model.domain.sync.BodyFatInput
import app.readylytics.health.core.model.domain.sync.BodyTemperatureInput
import app.readylytics.health.core.model.domain.sync.OxygenSaturationInput
import app.readylytics.health.core.model.domain.sync.WeightInput

internal data class FilteredVitals(
    val weights: List<WeightInput>,
    val bodyFatSamples: List<BodyFatInput>,
    val bloodPressureSamples: List<BloodPressureInput>,
    val oxygenSaturationSamples: List<OxygenSaturationInput>,
    val bodyTemperatureSamples: List<BodyTemperatureInput>,
)

/**
 * Maps the low-volume vitals half of one [RawBulkRecords] window into ingestion inputs and applies
 * the user's per-type device selection. Pure: no coordinator state, no Health Connect call, no
 * Room access -- extracted out of `HealthIngestionCoordinator.kt` so that file stays inside the
 * phase's "extract rather than grow it" size constraint, mirroring `HeartRateMapper`/`HrvMapper`.
 *
 * Row ids stay `"${record.id}_${timestampMs}"` with the opaque Health Connect record id carried
 * separately as `sourceId`, exactly as before the extraction -- these are upsert keys, so the
 * shape is load-bearing for idempotency.
 */
internal object VitalsInputMapper {
    fun mapAndFilter(
        raw: RawBulkRecords,
        prefs: UserPreferences,
    ): FilteredVitals {
        val (weights, bodyFat) = mapAndFilterBodyComp(raw, prefs)
        val (bp, spo2, temp) = mapAndFilterCardioVitals(raw, prefs)
        return FilteredVitals(
            weights = weights,
            bodyFatSamples = bodyFat,
            bloodPressureSamples = bp,
            oxygenSaturationSamples = spo2,
            bodyTemperatureSamples = temp,
        )
    }

    private fun mapAndFilterBodyComp(
        raw: RawBulkRecords,
        prefs: UserPreferences,
    ): Pair<List<WeightInput>, List<BodyFatInput>> {
        val deviceByType = prefs.deviceByDataType
        fun deviceFor(type: HealthDataType): String? = deviceByType[type.name]?.takeIf { it.isNotBlank() }

        val weightRecords = raw.weightRecords.dataOrEmpty()
        val bodyFatRecords = raw.bodyFatRecords.dataOrEmpty()
        val weightInputs =
            weightRecords.map {
                WeightInput(
                    id = "${it.id}_${it.time.toEpochMilli()}",
                    timestampMs = it.time.toEpochMilli(),
                    weightKg = it.weightKg,
                    deviceName = it.deviceName,
                    sourceId = it.id,
                )
            }
        val bodyFatInputs =
            bodyFatRecords.map {
                BodyFatInput(
                    id = "${it.id}_${it.time.toEpochMilli()}",
                    timestampMs = it.time.toEpochMilli(),
                    bodyFatPercent = it.percentage,
                    deviceName = it.deviceName,
                    sourceId = it.id,
                )
            }

        val filteredWeights =
            DeviceSourceFilter.filterToDevice(weightInputs, deviceFor(HealthDataType.WEIGHT)) { it.deviceName }
        val filteredBodyFat =
            DeviceSourceFilter.filterToDevice(bodyFatInputs, deviceFor(HealthDataType.BODY_FAT)) { it.deviceName }
        return Pair(filteredWeights, filteredBodyFat)
    }

    private fun mapAndFilterCardioVitals(
        raw: RawBulkRecords,
        prefs: UserPreferences,
    ): Triple<List<BloodPressureInput>, List<OxygenSaturationInput>, List<BodyTemperatureInput>> {
        val deviceByType = prefs.deviceByDataType
        fun deviceFor(type: HealthDataType): String? = deviceByType[type.name]?.takeIf { it.isNotBlank() }

        val bpRecords = raw.bloodPressureRecords.dataOrEmpty()
        val spo2Records = raw.spo2Records.dataOrEmpty()
        val tempRecords = raw.bodyTemperatureRecords.dataOrEmpty()
        val bpInputs =
            bpRecords.map {
                BloodPressureInput(
                    id = "${it.id}_${it.time.toEpochMilli()}",
                    timestampMs = it.time.toEpochMilli(),
                    systolicMmHg = it.systolicMmHg,
                    diastolicMmHg = it.diastolicMmHg,
                    deviceName = it.deviceName,
                    sourceId = it.id,
                )
            }
        val spo2Inputs =
            spo2Records.map {
                OxygenSaturationInput(
                    id = "${it.id}_${it.time.toEpochMilli()}",
                    timestampMs = it.time.toEpochMilli(),
                    percentage = it.percentage,
                    deviceName = it.deviceName,
                    sourceId = it.id,
                )
            }
        val tempInputs =
            tempRecords.map {
                BodyTemperatureInput(
                    id = "${it.id}_${it.time.toEpochMilli()}",
                    timestampMs = it.time.toEpochMilli(),
                    celsius = it.celsius,
                    deviceName = it.deviceName,
                    sourceId = it.id,
                )
            }

        val filteredBp =
            DeviceSourceFilter.filterToDevice(bpInputs, deviceFor(HealthDataType.BLOOD_PRESSURE)) { it.deviceName }
        val filteredSpo2 =
            DeviceSourceFilter.filterToDevice(
                spo2Inputs,
                deviceFor(HealthDataType.OXYGEN_SATURATION),
            ) { it.deviceName }
        val filteredTemp =
            DeviceSourceFilter.filterToDevice(
                tempInputs,
                deviceFor(HealthDataType.BODY_TEMPERATURE),
            ) { it.deviceName }
        return Triple(filteredBp, filteredSpo2, filteredTemp)
    }
}
