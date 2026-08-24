package app.readylytics.health.core.healthconnect.data.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.Change
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.BloodPressureRecord as HealthConnectBloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord as HealthConnectBodyFatRecord
import androidx.health.connect.client.records.HeartRateRecord as HealthConnectHeartRateRecord
import androidx.health.connect.client.records.WeightRecord as HealthConnectWeightRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.permission.HealthPermission
import app.readylytics.health.core.databaseschema.data.local.dao.*
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.StepRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.healthconnect.data.mapper.BloodPressureDataMapper
import app.readylytics.health.core.healthconnect.data.mapper.BodyFatDataMapper
import app.readylytics.health.core.healthconnect.data.mapper.BodyTemperatureDataMapper
import app.readylytics.health.core.healthconnect.data.mapper.OxygenSaturationDataMapper
import app.readylytics.health.core.healthconnect.data.mapper.WeightDataMapper
import app.readylytics.health.core.databaseschema.data.local.entity.SleepStageEntity
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.heartrate.ZoneThresholds
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.data.preferences.scoringZone
import app.readylytics.health.core.model.domain.model.*
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.healthconnect.domain.sync.HealthChangeSyncOutcome
import app.readylytics.health.core.healthconnect.domain.sync.HealthChangeSynchronizer
import app.readylytics.health.core.model.domain.sync.*
import app.readylytics.health.core.model.domain.sync.mappers.*
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.model.domain.util.logE
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthChangeSynchronizerImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val tokenStore: HealthChangeTokenStore,
        private val settingsRepo: SettingsRepository,
        private val transactionRunner: TransactionRunner,
        private val sleepSessionDao: SleepSessionDao,
        private val sleepStageDao: SleepStageDao,
        private val heartRateDao: HeartRateDao,
        private val hrvDao: HrvDao,
        private val workoutDao: WorkoutDao,
        private val weightRecordDao: WeightRecordDao,
        private val bodyFatRecordDao: BodyFatRecordDao,
        private val bloodPressureRecordDao: BloodPressureRecordDao,
        private val oxygenSaturationRecordDao: OxygenSaturationRecordDao,
        private val bodyTemperatureRecordDao: BodyTemperatureRecordDao,
        private val stepRecordDao: StepRecordDao,
        private val sourceRecordDao: SourceRecordDao,
    ) : HealthChangeSynchronizer {
        private val client by lazy { HealthConnectClient.getOrCreate(context) }

        override suspend fun applyPendingChanges(): HealthChangeSyncOutcome {
            val prefs = settingsRepo.userPreferences.first()
            val zoneId = prefs.scoringZone()
            val deviceByType = prefs.deviceByDataType

            val affectedDates = mutableSetOf<LocalDate>()
            val nextTokens = mutableMapOf<HealthDataType, String>()

            val grantedPermissions: Set<String> =
                try {
                    client.permissionController.getGrantedPermissions()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptySet()
                }

            for (dataType in HealthDataType.entries) {
                val token = tokenStore.get(dataType)
                if (token.isNullOrBlank()) {
                    val outcome = missingTokenOutcome(dataType, grantedPermissions)
                    if (outcome != null) return outcome
                    logD("HealthChangeSynchronizer") { "Skipping $dataType: permission not granted" }
                    continue
                }

                applyChangesForType(
                    dataType = dataType,
                    token = token,
                    deviceByType = deviceByType,
                    zoneId = zoneId,
                    prefs = prefs,
                    affectedDates = affectedDates,
                    nextTokens = nextTokens,
                )?.let { return it }
            }

            return HealthChangeSyncOutcome(
                affectedDates = affectedDates,
                requiresFullResync = false,
                nextTokens = nextTokens,
            )
        }

        private fun missingTokenOutcome(
            dataType: HealthDataType,
            grantedPermissions: Set<String>,
        ): HealthChangeSyncOutcome? {
            val typePermissions = recordClassesFor(dataType).map {
                HealthPermission.getReadPermission(it)
            }
            if (typePermissions.any { it in grantedPermissions }) {
                logD("HealthChangeSynchronizer") { "Token for $dataType is missing, requesting full resync" }
                return HealthChangeSyncOutcome(emptySet(), requiresFullResync = true)
            }
            return null
        }

        private suspend fun applyChangesForType(
            dataType: HealthDataType,
            token: String,
            deviceByType: Map<String, String>,
            zoneId: ZoneId,
            prefs: UserPreferences,
            affectedDates: MutableSet<LocalDate>,
            nextTokens: MutableMap<HealthDataType, String>,
        ): HealthChangeSyncOutcome? =
            try {
                var currentToken: String = token
                var hasMore = true
                while (hasMore) {
                    val response = client.getChanges(currentToken)
                    if (response.changesTokenExpired) {
                        logD("HealthChangeSynchronizer") {
                            "Token for $dataType is expired, requesting full resync"
                        }
                        return HealthChangeSyncOutcome(
                            affectedDates = emptySet(),
                            requiresFullResync = true,
                        )
                    }

                    val selectedDevice = deviceByType[dataType.name]?.takeIf { it.isNotBlank() }

                    // Apply this page of changes in a transaction
                    transactionRunner.runInTransaction {
                        processChangesPage(
                            dataType = dataType,
                            changes = response.changes,
                            affectedDates = affectedDates,
                            selectedDevice = selectedDevice,
                            zoneId = zoneId,
                            prefs = prefs,
                        )
                    }

                    // Return candidate token only after Room transaction succeeds. The sync
                    // coordinator persists candidates after derived summaries are durable.
                    currentToken = response.nextChangesToken
                    nextTokens[dataType] = currentToken
                    hasMore = response.hasMore
                }
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SecurityException) {
                logE("HealthChangeSynchronizer", e) {
                    "SecurityException reading changes for $dataType"
                }
                HealthChangeSyncOutcome(
                    affectedDates = emptySet(),
                    requiresFullResync = true,
                )
            } catch (e: Exception) {
                if (isTokenExpiredException(e)) {
                    logD("HealthChangeSynchronizer") {
                        "Change token expired for $dataType"
                    }
                    HealthChangeSyncOutcome(
                        affectedDates = emptySet(),
                        requiresFullResync = true,
                    )
                } else {
                    throw e
                }
            }

        override suspend fun commitTokens(tokens: Map<HealthDataType, String>) {
            if (tokens.isNotEmpty()) {
                tokenStore.putAll(tokens, System.currentTimeMillis())
            }
        }

        // Optional data types (weight, body fat, BP, SpO2, body temperature, steps) may lack
        // permission -- a permission-denied getChangesToken call must not abort the whole resync,
        // it just means that type gets no baseline token (mirrors the read-side degrade pattern).
        override suspend fun captureChangesTokens(): Map<HealthDataType, String> =
            HealthDataType.entries.mapNotNull { dataType ->
                try {
                    dataType to
                        client.getChangesToken(
                            ChangesTokenRequest(recordTypes = recordClassesFor(dataType)),
                        )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e.asHealthConnectSecurityCause() == null) throw e
                    logD("HealthChangeSynchronizer") {
                        "Changes token skipped for $dataType: permission not granted"
                    }
                    null
                }
            }.toMap()

        private suspend fun processChangesPage(
            dataType: HealthDataType,
            changes: List<Change>,
            affectedDates: MutableSet<LocalDate>,
            selectedDevice: String?,
            zoneId: ZoneId,
            prefs: UserPreferences,
        ) {
            for (change in changes) {
                when (change) {
                    is UpsertionChange -> {
                        val record = change.record
                        val deviceLabel = DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin)
                        val id = record.metadata.id

                        if (selectedDevice == null || deviceLabel == selectedDevice) {
                            val deletedDates = getAffectedDatesForDeletedRecord(dataType, id, zoneId)
                            affectedDates.addAll(deletedDates)
                            deleteRecordLocal(dataType, id)
                            affectedDates.addAll(getDatesForRecord(record, zoneId))
                            upsertRecord(dataType, record, prefs)
                        } else {
                            val deletedDates = getAffectedDatesForDeletedRecord(dataType, id, zoneId)
                            affectedDates.addAll(deletedDates)
                            deleteRecordLocal(dataType, id)
                        }
                    }
                    is DeletionChange -> {
                        val id = change.recordId
                        val deletedDates = getAffectedDatesForDeletedRecord(dataType, id, zoneId)
                        affectedDates.addAll(deletedDates)
                        deleteRecordLocal(dataType, id)
                    }
                }
            }
        }

        private suspend fun upsertRecord(
            dataType: HealthDataType,
            record: Record,
            prefs: UserPreferences,
        ) {
            when (dataType) {
                HealthDataType.SLEEP -> {
                    if (record is SleepSessionRecord) {
                        val domainRecord = record.toDomain()
                        val sleepInput = SleepDataMapper.mapSleepSession(domainRecord)
                        val sleepEntity = sleepInput.toEntity()
                        sleepSessionDao.upsertAll(listOf(sleepEntity))

                        val stageInputs = SleepDataMapper.mapSleepSessionStages(domainRecord)
                        val allStages = stageInputs.map { it.toEntity() }
                        sleepStageDao.deleteForSessions(listOf(sleepEntity.id))
                        sleepStageDao.upsertAll(allStages)
                    }
                }
                HealthDataType.HEART_RATE -> {
                    if (record is HealthConnectHeartRateRecord) {
                        val domainHr = record.toDomain()
                        // Resolve real session spans overlapping this record's own time range so the
                        // sample is tagged SLEEP/EXERCISE immediately instead of RESTING/sessionId=null
                        // until the next reconcile pass corrects it (HC-004).
                        val startMs = record.startTime.toEpochMilli()
                        val endMs = record.endTime.toEpochMilli()
                        val sleepSpans = sleepSessionDao.getOverlapping(startMs, endMs).map { it.toInput() }
                        val workoutSpans = workoutDao.getOverlapping(startMs, endMs).map { it.toInput() }
                        val hrInputs = HeartRateMapper.mapToInputs(listOf(domainHr), sleepSpans, workoutSpans)
                        val entities =
                            hrInputs.map { input ->
                                input.toEntity(
                                    sourceRecordDao.getOrCreateSourceRef(
                                        sourceRecordId = input.id.substringBefore('_'),
                                        recordType = "HEART_RATE",
                                        createdAtMs = input.timestampMs,
                                    ),
                                )
                            }
                        heartRateDao.upsertAll(entities)
                    }
                }
                HealthDataType.HRV -> {
                    if (record is HeartRateVariabilityRmssdRecord) {
                        val domainHrv = record.toDomain()
                        val sampleMs = record.time.toEpochMilli()
                        val sleepSpans = sleepSessionDao.getOverlapping(sampleMs, sampleMs).map { it.toInput() }
                        val hrvInputs = HrvMapper.mapToInputs(listOf(domainHrv), sleepSpans)
                        val entities =
                            hrvInputs.map { input ->
                                input.toEntity(
                                    sourceRecordDao.getOrCreateSourceRef(
                                        sourceRecordId = input.id.substringBefore('_'),
                                        recordType = "HRV",
                                        createdAtMs = input.timestampMs,
                                    ),
                                )
                            }
                        hrvDao.upsertAll(entities)
                    }
                }
                HealthDataType.EXERCISE -> {
                    if (record is ExerciseSessionRecord) {
                        val domainExercise = record.toDomain()
                        val thresholds =
                            ZoneThresholds.create(
                                prefs.zone1MinBpm,
                                prefs.zone1MaxBpm,
                                prefs.zone2MaxBpm,
                                prefs.zone3MaxBpm,
                                prefs.zone4MaxBpm,
                            )
                        // Compute metrics from already-stored HR rows overlapping this session so a
                        // workout upsert has non-zero TRIMP/zones/avgHr immediately (HC-004); a sample
                        // arriving in the very same changes batch is still corrected by the next
                        // reconcile pass, matching SessionLinkReconcilerImpl.recomputeWorkouts.
                        val hrSamples =
                            heartRateDao.getByTimeRange(
                                record.startTime.toEpochMilli(),
                                record.endTime.toEpochMilli(),
                            )
                        val hrSamplesMapped = hrSamples.map { sample ->
                            DomainHeartRateSample(
                                time = Instant.ofEpochMilli(sample.timestampMs),
                                beatsPerMinute = sample.beatsPerMinute
                            )
                        }
                        val metrics = ZoneThresholds.computeMetrics(
                            record.startTime.toEpochMilli(),
                            record.endTime.toEpochMilli(),
                            hrSamplesMapped,
                            thresholds
                        )
                        val workoutInput = WorkoutMapper.mapExerciseSession(domainExercise)
                        val entity = workoutInput.toEntity().copy(
                            durationMinutes = metrics.durationMinutes,
                            zone1Minutes = metrics.zoneMinutes[0],
                            zone2Minutes = metrics.zoneMinutes[1],
                            zone3Minutes = metrics.zoneMinutes[2],
                            zone4Minutes = metrics.zoneMinutes[3],
                            zone5Minutes = metrics.zoneMinutes[4],
                            trimp = metrics.trimp,
                            avgHr = metrics.avgHr
                        )
                        workoutDao.upsertAll(listOf(entity))
                    }
                }
                HealthDataType.WEIGHT -> {
                    if (record is HealthConnectWeightRecord) {
                        val domainWeight = record.toDomain()
                        val entity = WeightDataMapper.toEntities(listOf(domainWeight))
                        weightRecordDao.upsertAll(entity)
                    }
                }
                HealthDataType.BODY_FAT -> {
                    if (record is HealthConnectBodyFatRecord) {
                        val domainBodyFat = record.toDomain()
                        val entity = BodyFatDataMapper.toEntities(listOf(domainBodyFat))
                        bodyFatRecordDao.upsertAll(entity)
                    }
                }
                HealthDataType.BLOOD_PRESSURE -> {
                    if (record is HealthConnectBloodPressureRecord) {
                        val domainBloodPressure = record.toDomain()
                        val entity = BloodPressureDataMapper.toEntities(listOf(domainBloodPressure))
                        bloodPressureRecordDao.upsertAll(entity)
                    }
                }
                HealthDataType.OXYGEN_SATURATION -> {
                    if (record is OxygenSaturationRecord) {
                        val domainOxygen = record.toDomain()
                        val entity = OxygenSaturationDataMapper.toEntities(listOf(domainOxygen))
                        oxygenSaturationRecordDao.upsertAll(entity)
                    }
                }
                HealthDataType.BODY_TEMPERATURE -> {
                    if (record is BodyTemperatureRecord) {
                        val domainBodyTemperature = record.toDomain()
                        val entity = BodyTemperatureDataMapper.toEntities(listOf(domainBodyTemperature))
                        bodyTemperatureRecordDao.upsertAll(entity)
                    }
                }
                HealthDataType.STEPS -> {
                    if (record is StepsRecord) {
                        // Steps have no dedicated table for scoring (daily totals come from
                        // StepCountFetcher's aggregate reads) -- this row exists purely so a later
                        // DeletionChange for this record can resolve its own date range (HC-005).
                        stepRecordDao.upsertAll(
                            listOf(
                                StepRecordEntity(
                                    id = record.metadata.id,
                                    startTime = record.startTime.toEpochMilli(),
                                    endTime = record.endTime.toEpochMilli(),
                                    count = record.count,
                                    deviceName = DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin),
                                ),
                            ),
                        )
                    }
                }
            }
        }

        private fun recordClassesFor(dataType: HealthDataType): Set<kotlin.reflect.KClass<out Record>> =
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

        private fun isTokenExpiredException(e: Exception): Boolean {
            val msg = e.message?.lowercase() ?: ""
            return msg.contains("expired") || msg.contains("invalid token") || msg.contains("token not found")
        }

        private fun getDatesBetween(
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

        private fun getDateFor(
            time: Instant,
            zoneId: ZoneId,
        ): Set<LocalDate> = setOf(time.atZone(zoneId).toLocalDate())

        private fun getDatesForRecord(
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

        private suspend fun getAffectedDatesForDeletedRecord(
            dataType: HealthDataType,
            id: String,
            zoneId: ZoneId,
        ): Set<LocalDate> =
            when (dataType) {
                HealthDataType.SLEEP -> {
                    sleepSessionDao.getById(id)?.let {
                        getDatesBetween(Instant.ofEpochMilli(it.startTime), Instant.ofEpochMilli(it.endTime), zoneId)
                    } ?: emptySet()
                }
                HealthDataType.HEART_RATE -> {
                    sourceRecordDao.getSourceRef(id)?.let { ref ->
                        heartRateDao
                            .getBySourceRecordRef(ref)
                            .mapTo(mutableSetOf()) { getDateFor(Instant.ofEpochMilli(it.timestampMs), zoneId).single() }
                    } ?: emptySet()
                }
                HealthDataType.HRV -> {
                    sourceRecordDao.getSourceRef(id)?.let { ref ->
                        hrvDao
                            .getBySourceRecordRef(ref)
                            .mapTo(mutableSetOf()) { getDateFor(Instant.ofEpochMilli(it.timestampMs), zoneId).single() }
                    } ?: emptySet()
                }
                HealthDataType.EXERCISE -> {
                    workoutDao.getById(id)?.let {
                        getDatesBetween(Instant.ofEpochMilli(it.startTime), Instant.ofEpochMilli(it.endTime), zoneId)
                    } ?: emptySet()
                }
                HealthDataType.WEIGHT -> {
                    weightRecordDao
                        .getBySourceRecordId(id)
                        .mapTo(mutableSetOf()) { getDateFor(Instant.ofEpochMilli(it.timestampMs), zoneId).single() }
                }
                HealthDataType.BODY_FAT -> {
                    bodyFatRecordDao
                        .getBySourceRecordId(id)
                        .mapTo(mutableSetOf()) { getDateFor(Instant.ofEpochMilli(it.timestampMs), zoneId).single() }
                }
                HealthDataType.BLOOD_PRESSURE -> {
                    bloodPressureRecordDao
                        .getBySourceRecordId(id)
                        .mapTo(mutableSetOf()) { getDateFor(Instant.ofEpochMilli(it.timestampMs), zoneId).single() }
                }
                HealthDataType.OXYGEN_SATURATION -> {
                    oxygenSaturationRecordDao
                        .getBySourceRecordId(id)
                        .mapTo(mutableSetOf()) { getDateFor(Instant.ofEpochMilli(it.timestampMs), zoneId).single() }
                }
                HealthDataType.BODY_TEMPERATURE -> {
                    bodyTemperatureRecordDao
                        .getBySourceRecordId(id)
                        .mapTo(mutableSetOf()) { getDateFor(Instant.ofEpochMilli(it.timestampMs), zoneId).single() }
                }
                HealthDataType.STEPS -> {
                    // Resolve from the raw row upsertRecord's STEPS branch persisted, before
                    // deleteRecordLocal removes it (HC-005) -- must be called before the delete.
                    stepRecordDao.getById(id)?.let {
                        getDatesBetween(Instant.ofEpochMilli(it.startTime), Instant.ofEpochMilli(it.endTime), zoneId)
                    } ?: emptySet()
                }
            }

        private suspend fun deleteRecordLocal(
            dataType: HealthDataType,
            id: String,
        ) {
            when (dataType) {
                HealthDataType.SLEEP -> sleepSessionDao.deleteById(id)
                HealthDataType.HEART_RATE -> {
                    sourceRecordDao.getSourceRef(id)?.let { heartRateDao.deleteBySourceRecordRef(it) }
                    sourceRecordDao.deleteBySourceRecordId(id)
                }
                HealthDataType.HRV -> {
                    sourceRecordDao.getSourceRef(id)?.let { hrvDao.deleteBySourceRecordRef(it) }
                    sourceRecordDao.deleteBySourceRecordId(id)
                }
                HealthDataType.EXERCISE -> workoutDao.deleteById(id)
                HealthDataType.WEIGHT -> weightRecordDao.deleteBySourceRecordId(id)
                HealthDataType.BODY_FAT -> bodyFatRecordDao.deleteBySourceRecordId(id)
                HealthDataType.BLOOD_PRESSURE -> bloodPressureRecordDao.deleteBySourceRecordId(id)
                HealthDataType.OXYGEN_SATURATION -> oxygenSaturationRecordDao.deleteBySourceRecordId(id)
                HealthDataType.BODY_TEMPERATURE -> bodyTemperatureRecordDao.deleteBySourceRecordId(id)
                HealthDataType.STEPS -> stepRecordDao.deleteById(id)
            }
        }
    }

private fun SleepSessionInput.toEntity() =
    SleepSessionEntity(
        id = id,
        startTime = startTime,
        endTime = endTime,
        durationMinutes = durationMinutes,
        efficiency = efficiency,
        deepSleepMinutes = deepSleepMinutes,
        remSleepMinutes = remSleepMinutes,
        lightSleepMinutes = lightSleepMinutes,
        awakeMinutes = awakeMinutes,
        sleepScore = sleepScore,
        startZoneOffsetSeconds = startZoneOffsetSeconds,
        endZoneOffsetSeconds = endZoneOffsetSeconds,
        deviceName = deviceName,
    )

private fun SleepSessionEntity.toInput() =
    SleepSessionInput(
        id = id,
        startTime = startTime,
        endTime = endTime,
        durationMinutes = durationMinutes,
        efficiency = efficiency,
        deepSleepMinutes = deepSleepMinutes,
        remSleepMinutes = remSleepMinutes,
        lightSleepMinutes = lightSleepMinutes,
        awakeMinutes = awakeMinutes,
        sleepScore = sleepScore,
        startZoneOffsetSeconds = startZoneOffsetSeconds,
        endZoneOffsetSeconds = endZoneOffsetSeconds,
        deviceName = deviceName,
    )

private fun SleepStageInput.toEntity() =
    SleepStageEntity(
        sessionId = sessionId,
        stageType = stageType,
        startTime = startTime,
        endTime = endTime,
        durationMinutes = durationMinutes,
    )

private fun HeartRateInput.toEntity(sourceRecordRef: Long) =
    HeartRateRecordEntity(
        sourceRecordRef = sourceRecordRef,
        timestampMs = timestampMs,
        beatsPerMinute = beatsPerMinute,
        recordType = recordType,
        sessionId = sessionId,
        deviceName = deviceName,
    )

private fun HrvInput.toEntity(sourceRecordRef: Long) =
    HrvRecordEntity(
        sourceRecordRef = sourceRecordRef,
        timestampMs = timestampMs,
        rmssdMs = rmssdMs,
        recordType = recordType,
        sessionId = sessionId,
        deviceName = deviceName,
    )

private fun WorkoutInput.toEntity() =
    WorkoutRecordEntity(
        id = id,
        startTime = startTime,
        endTime = endTime,
        exerciseType = exerciseType,
        durationMinutes = durationMinutes,
        zone1Minutes = zone1Minutes,
        zone2Minutes = zone2Minutes,
        zone3Minutes = zone3Minutes,
        zone4Minutes = zone4Minutes,
        zone5Minutes = zone5Minutes,
        trimp = trimp,
        avgHr = avgHr,
        deviceName = deviceName,
        totalDistanceMeters = totalDistanceMeters,
        avgSpeedKmh = avgSpeedKmh,
        elevationGainMeters = elevationGainMeters,
        routeState = routeState,
    )

private fun WorkoutRecordEntity.toInput() =
    WorkoutInput(
        id = id,
        startTime = startTime,
        endTime = endTime,
        exerciseType = exerciseType,
        durationMinutes = durationMinutes,
        zone1Minutes = zone1Minutes,
        zone2Minutes = zone2Minutes,
        zone3Minutes = zone3Minutes,
        zone4Minutes = zone4Minutes,
        zone5Minutes = zone5Minutes,
        trimp = trimp,
        avgHr = avgHr,
        deviceName = deviceName,
        totalDistanceMeters = totalDistanceMeters,
        avgSpeedKmh = avgSpeedKmh,
        elevationGainMeters = elevationGainMeters,
        routeState = routeState,
    )
