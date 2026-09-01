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
import java.time.Clock
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
        private val clock: Clock = Clock.systemDefaultZone(),
        private val transactionRunner: TransactionRunner,
        private val healthIngestionStore: HealthIngestionStore,
        private val changeIngestionStore: HealthChangeIngestionStore,
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
                tokenStore.putAll(tokens, clock.millis())
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
            val spans = pageSessionSpans(dataType, changes)
            for (change in changes) {
                when (change) {
                    is UpsertionChange -> {
                        val record = change.record
                        val deviceLabel = DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin)
                        val id = record.metadata.id

                        affectedDates.addAll(changeIngestionStore.affectedDatesForRecord(dataType, id, zoneId))
                        changeIngestionStore.deleteRecord(dataType, id)

                        if (selectedDevice == null || deviceLabel == selectedDevice) {
                            affectedDates.addAll(getDatesForRecord(record, zoneId))
                            upsertRecord(dataType, record, prefs, spans)
                        }
                    }
                    is DeletionChange -> {
                        val id = change.recordId
                        affectedDates.addAll(changeIngestionStore.affectedDatesForRecord(dataType, id, zoneId))
                        changeIngestionStore.deleteRecord(dataType, id)
                    }
                }
            }
        }

        /**
         * R2-HC-003: one `sessionSpansOverlapping` call for the whole page's time range, instead of
         * one per HEART_RATE/HRV record. Only fetched for the two data types that consume spans.
         */
        private suspend fun pageSessionSpans(dataType: HealthDataType, changes: List<Change>): SessionSpans {
            val spanConsumingTypes = setOf(HealthDataType.HEART_RATE, HealthDataType.HRV)
            val upserts =
                if (dataType in spanConsumingTypes) {
                    changes.filterIsInstance<UpsertionChange>()
                } else {
                    emptyList()
                }
            if (upserts.isEmpty()) return SessionSpans(emptyList(), emptyList())
            val starts = upserts.map { recordStartMs(it.record) }
            val ends = upserts.map { recordEndMs(it.record) }
            return changeIngestionStore.sessionSpansOverlapping(starts.min(), ends.max())
        }

        private fun recordStartMs(record: Record): Long =
            when (record) {
                is HealthConnectHeartRateRecord -> record.startTime.toEpochMilli()
                is HeartRateVariabilityRmssdRecord -> record.time.toEpochMilli()
                else -> error("pageSessionSpans called for unsupported record type")
            }

        private fun recordEndMs(record: Record): Long =
            when (record) {
                is HealthConnectHeartRateRecord -> record.endTime.toEpochMilli()
                is HeartRateVariabilityRmssdRecord -> record.time.toEpochMilli()
                else -> error("pageSessionSpans called for unsupported record type")
            }

        private suspend fun upsertRecord(
            dataType: HealthDataType,
            record: Record,
            prefs: UserPreferences,
            spans: SessionSpans,
        ) {
            when (dataType) {
                HealthDataType.SLEEP -> upsertSleep(record)
                HealthDataType.HEART_RATE -> upsertHeartRate(record, spans)
                HealthDataType.HRV -> upsertHrv(record, spans)
                HealthDataType.EXERCISE -> upsertExercise(record, prefs)
                HealthDataType.WEIGHT -> upsertWeight(record)
                HealthDataType.BODY_FAT -> upsertBodyFat(record)
                HealthDataType.BLOOD_PRESSURE -> upsertBloodPressure(record)
                HealthDataType.OXYGEN_SATURATION -> upsertOxygenSaturation(record)
                HealthDataType.BODY_TEMPERATURE -> upsertBodyTemperature(record)
                HealthDataType.STEPS -> upsertSteps(record)
            }
        }

        private suspend fun upsertSleep(record: Record) {
            if (record !is SleepSessionRecord) return
            val domainRecord = record.toDomain()
            val sleepInput = SleepDataMapper.mapSleepSession(domainRecord)
            val stageInputs = SleepDataMapper.mapSleepSessionStages(domainRecord)
            healthIngestionStore.persist(
                emptyBatch(sleepSessions = listOf(sleepInput), sleepStages = stageInputs),
            )
        }

        private suspend fun upsertHeartRate(record: Record, spans: SessionSpans) {
            if (record !is HealthConnectHeartRateRecord) return
            val domainHr = record.toDomain()
            // Real session spans overlapping this record's own time range so the sample is tagged
            // SLEEP/EXERCISE immediately instead of RESTING/sessionId=null until the next reconcile
            // pass corrects it (HC-004).
            val hrInputs = HeartRateMapper.mapToInputs(listOf(domainHr), spans.sleepSessions, spans.workouts)
            healthIngestionStore.persistHeartRateSamples(hrInputs)
        }

        private suspend fun upsertHrv(record: Record, spans: SessionSpans) {
            if (record !is HeartRateVariabilityRmssdRecord) return
            val domainHrv = record.toDomain()
            val hrvInputs = HrvMapper.mapToInputs(listOf(domainHrv), spans.sleepSessions)
            healthIngestionStore.persistHrvSamples(hrvInputs)
        }

        private suspend fun upsertExercise(record: Record, prefs: UserPreferences) {
            if (record !is ExerciseSessionRecord) return
            val distanceTotal = sessionTotalFor<DistanceRecord>(client, record) { it.toIntervalTotal() }
            val elevationTotal = sessionTotalFor<ElevationGainedRecord>(client, record) { it.toIntervalTotal() }
            val domainExercise = record.toDomain(
                routeResult = record.exerciseRouteResult,
                totalDistanceMeters = distanceTotal,
                elevationGainMeters = elevationTotal,
            )
            val thresholds = ZoneThresholds.create(
                prefs.zone1MinBpm, prefs.zone1MaxBpm, prefs.zone2MaxBpm,
                prefs.zone3MaxBpm, prefs.zone4MaxBpm,
            )
            // Provisional metrics from already-stored HR overlapping this session so a workout
            // upsert has non-zero TRIMP/zones/avgHr immediately (HC-004); a sample arriving in the
            // same changes batch is corrected by the next reconcile pass. EXERCISE-only filter
            // matches SessionLinkReconcilerImpl.recomputeWorkouts.
            val hrSamples = changeIngestionStore.heartRateSamplesForMetrics(
                RecordType.EXERCISE.name,
                record.startTime.toEpochMilli(),
                record.endTime.toEpochMilli(),
            )
            val metrics = ZoneThresholds.computeMetrics(
                record.startTime.toEpochMilli(), record.endTime.toEpochMilli(), hrSamples, thresholds,
            )
            val workoutInput = WorkoutMapper.mapExerciseSession(domainExercise).copy(
                durationMinutes = metrics.durationMinutes,
                zone1Minutes = metrics.zoneMinutes[0],
                zone2Minutes = metrics.zoneMinutes[1],
                zone3Minutes = metrics.zoneMinutes[2],
                zone4Minutes = metrics.zoneMinutes[3],
                zone5Minutes = metrics.zoneMinutes[4],
                trimp = metrics.trimp,
                avgHr = metrics.avgHr,
            )
            // modelTrimp/route-field preservation across re-upserts is handled inside
            // RoomHealthIngestionStore.persist() already (mirrors bulk-path behavior).
            healthIngestionStore.persist(emptyBatch(workouts = listOf(workoutInput)))
        }

        private suspend fun upsertWeight(record: Record) {
            if (record !is HealthConnectWeightRecord) return
            healthIngestionStore.persist(emptyBatch(weights = listOf(record.toDomain().toWeightInput())))
        }

        private suspend fun upsertBodyFat(record: Record) {
            if (record !is HealthConnectBodyFatRecord) return
            healthIngestionStore.persist(emptyBatch(bodyFatSamples = listOf(record.toDomain().toBodyFatInput())))
        }

        private suspend fun upsertBloodPressure(record: Record) {
            if (record !is HealthConnectBloodPressureRecord) return
            healthIngestionStore.persist(
                emptyBatch(bloodPressureSamples = listOf(record.toDomain().toBloodPressureInput())),
            )
        }

        private suspend fun upsertOxygenSaturation(record: Record) {
            if (record !is OxygenSaturationRecord) return
            healthIngestionStore.persist(
                emptyBatch(oxygenSaturationSamples = listOf(record.toDomain().toOxygenSaturationInput())),
            )
        }

        private suspend fun upsertBodyTemperature(record: Record) {
            if (record !is BodyTemperatureRecord) return
            healthIngestionStore.persist(
                emptyBatch(bodyTemperatureSamples = listOf(record.toDomain().toBodyTemperatureInput())),
            )
        }

        private suspend fun upsertSteps(record: Record) {
            if (record !is StepsRecord) return
            // Steps have no dedicated scoring table (daily totals come from StepCountFetcher's
            // aggregate reads) -- this row exists purely so a later DeletionChange for this record
            // can resolve its own date range (HC-005).
            val stepInput = StepRecordInput(
                id = record.metadata.id,
                startTime = record.startTime.toEpochMilli(),
                endTime = record.endTime.toEpochMilli(),
                count = record.count,
                deviceName = DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin),
            )
            healthIngestionStore.persist(emptyBatch(stepRecords = listOf(stepInput)))
        }
    }
