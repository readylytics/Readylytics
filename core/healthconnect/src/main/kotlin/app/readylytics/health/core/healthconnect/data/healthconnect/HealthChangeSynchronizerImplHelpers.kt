package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.records.*
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.sync.HealthChangeIngestionStore
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.PreparedWorkout
import app.readylytics.health.core.model.domain.sync.SessionSpans
import app.readylytics.health.core.model.domain.sync.emptyBatch

internal suspend fun upsertRecords(
    dataType: HealthDataType,
    records: List<Record>,
    prefs: UserPreferences,
    spans: SessionSpans,
    preparedWorkouts: Map<String, PreparedWorkout>,
    healthIngestionStore: HealthIngestionStore,
    changeIngestionStore: HealthChangeIngestionStore,
) {
    when (dataType) {
        HealthDataType.SLEEP -> upsertSleeps(records, healthIngestionStore)
        HealthDataType.HEART_RATE -> upsertHeartRates(records, spans, healthIngestionStore)
        HealthDataType.HRV -> upsertHrvs(records, spans, healthIngestionStore)
        HealthDataType.EXERCISE -> upsertExercises(records, prefs, preparedWorkouts, changeIngestionStore)
        HealthDataType.WEIGHT -> upsertWeights(records, healthIngestionStore)
        HealthDataType.BODY_FAT -> upsertBodyFats(records, healthIngestionStore)
        HealthDataType.BLOOD_PRESSURE -> upsertBloodPressures(records, healthIngestionStore)
        HealthDataType.OXYGEN_SATURATION -> upsertOxygenSaturations(records, healthIngestionStore)
        HealthDataType.BODY_TEMPERATURE -> upsertBodyTemperatures(records, healthIngestionStore)
        HealthDataType.STEPS -> upsertStepsBatch(records, healthIngestionStore)
        HealthDataType.VO2_MAX -> upsertVo2Maxes(records, healthIngestionStore)
    }
}

private suspend fun upsertSleeps(records: List<Record>, healthIngestionStore: HealthIngestionStore) {
    val sleepSessions = mutableListOf<app.readylytics.health.core.model.domain.sync.SleepSessionInput>()
    val sleepStages = mutableListOf<app.readylytics.health.core.model.domain.sync.SleepStageInput>()
    for (record in records) {
        if (record !is SleepSessionRecord) continue
        val domainRecord = record.toDomain()
        sleepSessions.add(SleepDataMapper.mapSleepSession(domainRecord))
        sleepStages.addAll(SleepDataMapper.mapSleepSessionStages(domainRecord))
    }
    healthIngestionStore.persist(emptyBatch(sleepSessions = sleepSessions, sleepStages = sleepStages))
}

private suspend fun upsertHeartRates(records: List<Record>, spans: SessionSpans, healthIngestionStore: HealthIngestionStore) {
    val domainHrs = records.filterIsInstance<HealthConnectHeartRateRecord>().map { it.toDomain() }
    if (domainHrs.isEmpty()) return
    val hrSources = HeartRateMapper.mapToInputs(domainHrs, spans.sleepSessions, spans.workouts)
    healthIngestionStore.replaceHeartRateSources(hrSources)
}

private suspend fun upsertHrvs(records: List<Record>, spans: SessionSpans, healthIngestionStore: HealthIngestionStore) {
    val domainHrvs = records.filterIsInstance<HeartRateVariabilityRmssdRecord>().map { it.toDomain() }
    if (domainHrvs.isEmpty()) return
    val hrvSources = HrvMapper.mapToInputs(domainHrvs, spans.sleepSessions)
    healthIngestionStore.replaceHrvSources(hrvSources)
}

private suspend fun upsertExercises(
    records: List<Record>,
    prefs: UserPreferences,
    preparedWorkouts: Map<String, PreparedWorkout>,
    changeIngestionStore: HealthChangeIngestionStore,
) {
    val thresholds = app.readylytics.health.core.model.domain.model.ZoneThresholds.create(
        prefs.zone1MinBpm, prefs.zone1MaxBpm, prefs.zone2MaxBpm,
        prefs.zone3MaxBpm, prefs.zone4MaxBpm,
    )
    val toPersist = mutableListOf<PreparedWorkout>()
    for (record in records) {
        val prepared = preparedWorkouts[record.metadata.id]
        if (record is ExerciseSessionRecord && prepared != null) {
            val hrSamples = changeIngestionStore.heartRateSamplesForMetrics(
                app.readylytics.health.core.model.domain.model.RecordType.EXERCISE.name,
                record.startTime.toEpochMilli(),
                record.endTime.toEpochMilli(),
            )
            val metrics = app.readylytics.health.core.model.domain.model.ZoneThresholds.computeMetrics(
                record.startTime.toEpochMilli(), record.endTime.toEpochMilli(), hrSamples, thresholds,
            )
            val workoutWithMetrics = prepared.workout.copy(
                durationMinutes = metrics.durationMinutes,
                zone1Minutes = metrics.zoneMinutes[0] ?: 0f,
                zone2Minutes = metrics.zoneMinutes[1] ?: 0f,
                zone3Minutes = metrics.zoneMinutes[2] ?: 0f,
                zone4Minutes = metrics.zoneMinutes[3] ?: 0f,
                zone5Minutes = metrics.zoneMinutes[4] ?: 0f,
                trimp = metrics.trimp,
                avgHr = metrics.avgHr,
            )
            toPersist.add(prepared.copy(workout = workoutWithMetrics))
        }
    }
    if (toPersist.isNotEmpty()) {
        changeIngestionStore.persistPreparedWorkouts(toPersist)
    }
}

private suspend fun upsertWeights(records: List<Record>, healthIngestionStore: HealthIngestionStore) {
    val inputs = records.filterIsInstance<WeightRecord>().map { it.toDomain().toWeightInput() }
    healthIngestionStore.persist(emptyBatch(weights = inputs))
}

private suspend fun upsertBodyFats(records: List<Record>, healthIngestionStore: HealthIngestionStore) {
    val inputs = records.filterIsInstance<BodyFatRecord>().map { it.toDomain().toBodyFatInput() }
    healthIngestionStore.persist(emptyBatch(bodyFatSamples = inputs))
}

private suspend fun upsertBloodPressures(records: List<Record>, healthIngestionStore: HealthIngestionStore) {
    val inputs = records.filterIsInstance<BloodPressureRecord>().map { it.toDomain().toBloodPressureInput() }
    healthIngestionStore.persist(emptyBatch(bloodPressureSamples = inputs))
}

private suspend fun upsertOxygenSaturations(records: List<Record>, healthIngestionStore: HealthIngestionStore) {
    val inputs = records.filterIsInstance<OxygenSaturationRecord>().map { it.toDomain().toOxygenSaturationInput() }
    healthIngestionStore.persist(emptyBatch(oxygenSaturationSamples = inputs))
}

private suspend fun upsertBodyTemperatures(records: List<Record>, healthIngestionStore: HealthIngestionStore) {
    val inputs = records.filterIsInstance<BodyTemperatureRecord>().map { it.toDomain().toBodyTemperatureInput() }
    healthIngestionStore.persist(emptyBatch(bodyTemperatureSamples = inputs))
}

private suspend fun upsertStepsBatch(records: List<Record>, healthIngestionStore: HealthIngestionStore) {
    val inputs = records.filterIsInstance<StepsRecord>().map { record ->
        app.readylytics.health.core.model.domain.sync.StepRecordInput(
            id = record.metadata.id,
            startTime = record.startTime.toEpochMilli(),
            endTime = record.endTime.toEpochMilli(),
            count = record.count,
            deviceName = app.readylytics.health.core.model.domain.model.DeviceLabel.from(
                record.metadata.device, record.metadata.dataOrigin
            ),
        )
    }
    healthIngestionStore.persist(emptyBatch(stepRecords = inputs))
}

private suspend fun upsertVo2Maxes(records: List<Record>, healthIngestionStore: HealthIngestionStore) {
    val inputs = records.filterIsInstance<Vo2MaxRecord>().map { record ->
        val domain = record.toDomain()
        app.readylytics.health.core.model.domain.sync.Vo2MaxInput(
            id = domain.id,
            timestampMs = domain.time.toEpochMilli(),
            vo2Max = domain.vo2MillilitersPerMinuteKilogram.toFloat(),
            measurementMethod = domain.measurementMethod,
            deviceName = domain.deviceName,
        )
    }
    healthIngestionStore.persist(emptyBatch(vo2MaxSamples = inputs))
}
