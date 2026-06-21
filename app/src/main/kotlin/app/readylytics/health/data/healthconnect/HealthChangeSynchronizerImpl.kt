package app.readylytics.health.data.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.Change
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ChangesTokenRequest
import app.readylytics.health.data.local.dao.*
import app.readylytics.health.data.mapper.BloodPressureDataMapper
import app.readylytics.health.data.mapper.BodyFatDataMapper
import app.readylytics.health.data.mapper.OxygenSaturationDataMapper
import app.readylytics.health.data.mapper.WeightDataMapper
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.scoringZone
import app.readylytics.health.domain.model.*
import app.readylytics.health.domain.repository.TransactionRunner
import app.readylytics.health.domain.sync.HealthChangeSyncOutcome
import app.readylytics.health.domain.sync.HealthChangeSynchronizer
import app.readylytics.health.domain.sync.HealthChangeTokenStore
import app.readylytics.health.domain.util.logD
import app.readylytics.health.domain.util.logE
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
        @ApplicationContext private val context: Context,
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
    ) : HealthChangeSynchronizer {
        private val client by lazy { HealthConnectClient.getOrCreate(context) }

        override suspend fun applyPendingChanges(): HealthChangeSyncOutcome {
            val prefs = settingsRepo.userPreferences.first()
            val zoneId = prefs.scoringZone()
            val deviceByType = prefs.deviceByDataType

            val affectedDates = mutableSetOf<LocalDate>()

            for (dataType in HealthDataType.entries) {
                val token = tokenStore.get(dataType)
                if (token.isNullOrBlank()) {
                    logD("HealthChangeSynchronizer") { "Token for $dataType is missing, requesting full resync" }
                    return HealthChangeSyncOutcome(emptySet(), requiresFullResync = true)
                }

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

                        // Advance token only after Room transaction succeeds
                        currentToken = response.nextChangesToken
                        tokenStore.put(dataType, currentToken, System.currentTimeMillis())
                        hasMore = response.hasMore
                    }
                } catch (e: SecurityException) {
                    logE("HealthChangeSynchronizer", e) {
                        "SecurityException reading changes for $dataType"
                    }
                    return HealthChangeSyncOutcome(
                        affectedDates = emptySet(),
                        requiresFullResync = true,
                    )
                } catch (e: Exception) {
                    if (isTokenExpiredException(e)) {
                        logD("HealthChangeSynchronizer") {
                            "Change token expired for $dataType"
                        }
                        return HealthChangeSyncOutcome(
                            affectedDates = emptySet(),
                            requiresFullResync = true,
                        )
                    }
                    throw e
                }
            }

            return HealthChangeSyncOutcome(affectedDates, requiresFullResync = false)
        }

        override suspend fun refreshTokensAfterFullResync() {
            logD("HealthChangeSynchronizer") { "Refreshing all change tokens after successful full resync" }
            for (dataType in HealthDataType.entries) {
                try {
                    val recordClass = recordClassFor(dataType)
                    val token =
                        client.getChangesToken(
                            ChangesTokenRequest(recordTypes = setOf(recordClass)),
                        )
                    tokenStore.put(dataType, token, System.currentTimeMillis())
                } catch (e: SecurityException) {
                    logE(
                        "HealthChangeSynchronizer",
                        e,
                    ) { "Failed to refresh token for $dataType due to security exception" }
                } catch (e: Exception) {
                    logE("HealthChangeSynchronizer", e) { "Failed to refresh token for $dataType" }
                }
            }
        }

        private suspend fun processChangesPage(
            dataType: HealthDataType,
            changes: List<Change>,
            affectedDates: MutableSet<LocalDate>,
            selectedDevice: String?,
            zoneId: ZoneId,
            prefs: app.readylytics.health.data.preferences.UserPreferences,
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
            prefs: app.readylytics.health.data.preferences.UserPreferences,
        ) {
            when (dataType) {
                HealthDataType.SLEEP -> {
                    if (record is SleepSessionRecord) {
                        val domainRecord = record.toDomain()
                        val sleepEntity = SleepDataMapper.mapSleepSession(domainRecord)
                        sleepSessionDao.upsertAll(listOf(sleepEntity))

                        val allStages = SleepDataMapper.mapSleepSessionStages(domainRecord)
                        sleepStageDao.deleteForSessions(listOf(sleepEntity.id))
                        sleepStageDao.upsertAll(allStages)
                    }
                }
                HealthDataType.HEART_RATE -> {
                    if (record is HeartRateRecord) {
                        val domainHr = record.toDomain()
                        val entities = HeartRateMapper.mapToEntities(listOf(domainHr), emptyList(), emptyList())
                        heartRateDao.upsertAll(entities)
                    }
                }
                HealthDataType.HRV -> {
                    if (record is HeartRateVariabilityRmssdRecord) {
                        val domainHrv = record.toDomain()
                        val entities = HrvMapper.mapToEntities(listOf(domainHrv), emptyList())
                        hrvDao.upsertAll(entities)
                    }
                }
                HealthDataType.EXERCISE -> {
                    if (record is ExerciseSessionRecord) {
                        val domainExercise = record.toDomain()
                        val thresholds =
                            WorkoutMapper.zoneThresholds(
                                prefs.zone1MinBpm,
                                prefs.zone1MaxBpm,
                                prefs.zone2MaxBpm,
                                prefs.zone3MaxBpm,
                                prefs.zone4MaxBpm,
                            )
                        val entity = WorkoutMapper.mapExerciseSession(domainExercise, emptyList(), thresholds)
                        workoutDao.upsertAll(listOf(entity))
                    }
                }
                HealthDataType.WEIGHT -> {
                    if (record is WeightRecord) {
                        val domainWeight = record.toDomain()
                        val entity = WeightDataMapper.toEntities(listOf(domainWeight))
                        weightRecordDao.upsertAll(entity)
                    }
                }
                HealthDataType.BODY_FAT -> {
                    if (record is BodyFatRecord) {
                        val domainBodyFat = record.toDomain()
                        val entity = BodyFatDataMapper.toEntities(listOf(domainBodyFat))
                        bodyFatRecordDao.upsertAll(entity)
                    }
                }
                HealthDataType.BLOOD_PRESSURE -> {
                    if (record is BloodPressureRecord) {
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
                HealthDataType.STEPS -> {
                    // Steps do not have a dedicated DB table, so we do nothing.
                }
            }
        }

        private fun recordClassFor(dataType: HealthDataType): kotlin.reflect.KClass<out Record> =
            when (dataType) {
                HealthDataType.EXERCISE -> ExerciseSessionRecord::class
                HealthDataType.STEPS -> StepsRecord::class
                HealthDataType.BODY_FAT -> BodyFatRecord::class
                HealthDataType.WEIGHT -> WeightRecord::class
                HealthDataType.SLEEP -> SleepSessionRecord::class
                HealthDataType.BLOOD_PRESSURE -> BloodPressureRecord::class
                HealthDataType.HEART_RATE -> HeartRateRecord::class
                HealthDataType.HRV -> HeartRateVariabilityRmssdRecord::class
                HealthDataType.OXYGEN_SATURATION -> OxygenSaturationRecord::class
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
                is HeartRateRecord -> getDatesBetween(record.startTime, record.endTime, zoneId)
                is HeartRateVariabilityRmssdRecord -> getDateFor(record.time, zoneId)
                is WeightRecord -> getDateFor(record.time, zoneId)
                is BodyFatRecord -> getDateFor(record.time, zoneId)
                is BloodPressureRecord -> getDateFor(record.time, zoneId)
                is OxygenSaturationRecord -> getDateFor(record.time, zoneId)
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
                    heartRateDao
                        .getBySourceRecordId(id)
                        .mapTo(mutableSetOf()) { getDateFor(Instant.ofEpochMilli(it.timestampMs), zoneId).single() }
                }
                HealthDataType.HRV -> {
                    hrvDao
                        .getBySourceRecordId(id)
                        .mapTo(mutableSetOf()) { getDateFor(Instant.ofEpochMilli(it.timestampMs), zoneId).single() }
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
                HealthDataType.STEPS -> emptySet()
            }

        private suspend fun deleteRecordLocal(
            dataType: HealthDataType,
            id: String,
        ) {
            when (dataType) {
                HealthDataType.SLEEP -> sleepSessionDao.deleteById(id)
                HealthDataType.HEART_RATE -> heartRateDao.deleteBySourceRecordId(id)
                HealthDataType.HRV -> hrvDao.deleteBySourceRecordId(id)
                HealthDataType.EXERCISE -> workoutDao.deleteById(id)
                HealthDataType.WEIGHT -> weightRecordDao.deleteBySourceRecordId(id)
                HealthDataType.BODY_FAT -> bodyFatRecordDao.deleteBySourceRecordId(id)
                HealthDataType.BLOOD_PRESSURE -> bloodPressureRecordDao.deleteBySourceRecordId(id)
                HealthDataType.OXYGEN_SATURATION -> oxygenSaturationRecordDao.deleteBySourceRecordId(id)
                HealthDataType.STEPS -> Unit
            }
        }

        private fun SleepSessionRecord.toDomain(): DomainSleepSessionRecord =
            DomainSleepSessionRecord(
                id = metadata.id,
                startTime = startTime,
                endTime = endTime,
                startZoneOffsetSeconds = startZoneOffset?.totalSeconds,
                endZoneOffsetSeconds = endZoneOffset?.totalSeconds,
                deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
                stages =
                    stages.map { stage ->
                        DomainSleepStage(
                            startTime = stage.startTime,
                            endTime = stage.endTime,
                            stageType =
                                when (stage.stage) {
                                    SleepSessionRecord.STAGE_TYPE_DEEP -> DomainSleepStageType.DEEP
                                    SleepSessionRecord.STAGE_TYPE_REM -> DomainSleepStageType.REM
                                    SleepSessionRecord.STAGE_TYPE_LIGHT,
                                    SleepSessionRecord.STAGE_TYPE_SLEEPING,
                                    -> DomainSleepStageType.LIGHT
                                    SleepSessionRecord.STAGE_TYPE_AWAKE,
                                    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                                    -> DomainSleepStageType.AWAKE
                                    else -> DomainSleepStageType.UNKNOWN
                                },
                        )
                    },
            )

        private fun HeartRateRecord.toDomain(): DomainHeartRateRecord =
            DomainHeartRateRecord(
                id = metadata.id,
                deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
                samples =
                    samples.map { sample ->
                        DomainHeartRateSample(
                            time = sample.time,
                            beatsPerMinute = sample.beatsPerMinute.toInt(),
                        )
                    },
            )

        private fun HeartRateVariabilityRmssdRecord.toDomain(): DomainHrvRecord =
            DomainHrvRecord(
                id = metadata.id,
                time = time,
                rmssdMs = heartRateVariabilityMillis.toFloat(),
                deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
            )

        private fun ExerciseSessionRecord.toDomain(): DomainExerciseSessionRecord =
            DomainExerciseSessionRecord(
                id = metadata.id,
                startTime = startTime,
                endTime = endTime,
                exerciseType = exerciseType.toString(),
                deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
            )

        private fun WeightRecord.toDomain(): DomainWeightRecord =
            DomainWeightRecord(
                id = metadata.id,
                time = time,
                weightKg = weight.inKilograms.toFloat(),
                deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
            )

        private fun BodyFatRecord.toDomain(): DomainBodyFatRecord =
            DomainBodyFatRecord(
                id = metadata.id,
                time = time,
                percentage = percentage.value.toFloat(),
                deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
            )

        private fun BloodPressureRecord.toDomain(): DomainBloodPressureRecord =
            DomainBloodPressureRecord(
                id = metadata.id,
                time = time,
                systolicMmHg = systolic.inMillimetersOfMercury.toInt(),
                diastolicMmHg = diastolic.inMillimetersOfMercury.toInt(),
                deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
            )

        private fun OxygenSaturationRecord.toDomain(): DomainOxygenSaturationRecord =
            DomainOxygenSaturationRecord(
                id = metadata.id,
                time = time,
                percentage = percentage.value.toFloat(),
                deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
            )
    }
