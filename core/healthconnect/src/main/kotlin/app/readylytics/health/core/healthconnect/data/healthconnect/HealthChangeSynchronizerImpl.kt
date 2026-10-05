package app.readylytics.health.core.healthconnect.data.healthconnect

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
import app.readylytics.health.core.model.domain.preferences.UserPreferences
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
import app.readylytics.health.core.model.domain.util.logI
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
        private val client: HealthConnectClient,
        private val tokenStore: HealthChangeTokenStore,
        private val settingsRepo: SettingsRepository,
        private val clock: Clock = Clock.systemDefaultZone(),
        private val transactionRunner: TransactionRunner,
        private val healthIngestionStore: HealthIngestionStore,
        private val changeIngestionStore: HealthChangeIngestionStore,
        private val workoutReadPreparer: WorkoutReadPreparer,
        private val workoutEnrichmentRefresher: WorkoutEnrichmentRefresher =
            WorkoutEnrichmentRefresher(client, changeIngestionStore),
    ) : HealthChangeSynchronizer {
    private class SyncBudget {
        val startNanos = System.nanoTime()
        var pagesApplied = 0
        fun isExhausted(): Boolean {
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L
            return elapsedMs >= app.readylytics.health.core.healthconnect.domain.sync.DEFAULT_CHANGES_APPLY_BUDGET_MS || pagesApplied >= app.readylytics.health.core.healthconnect.domain.sync.MAX_CHANGE_PAGES_PER_RUN
        }
    }

        private val stagedIntervalTokens = mutableMapOf<String, String>()

        override suspend fun applyPendingChanges(): HealthChangeSyncOutcome {
            stagedIntervalTokens.clear()
            val budget = SyncBudget()
            val prefs = settingsRepo.userPreferences.first()
            val zoneId = prefs.scoringZone()
            val deviceByType = prefs.deviceByDataType

            val affectedDates = mutableSetOf<LocalDate>()
            val nextTokens = mutableMapOf<HealthDataType, String>()

            // A failed lookup must fail this sync (the caller retries), never read as "nothing
            // granted": that would suspend -- i.e. delete -- every change token, and the next sync
            // would find them missing and escalate to a full historical resync.
            val grantedPermissions: Set<String> = client.permissionController.getGrantedPermissions()

            for (dataType in HealthDataType.entries) {
                val typePermissions =
                    recordClassesFor(dataType).map {
                        HealthPermission.getReadPermission(it)
                    }
                val isGranted = typePermissions.all { it in grantedPermissions }
                val token = tokenStore.get(dataType)

                if (!isGranted) {
                    if (!token.isNullOrBlank()) {
                        logI("HealthChangeSynchronizer") { "Permission revoked for $dataType: suspending token" }
                        tokenStore.suspendType(dataType)
                    }
                    logD("HealthChangeSynchronizer") { "Skipping $dataType: permission not granted" }
                    continue
                }

                if (token.isNullOrBlank()) {
                    logI("HealthChangeSynchronizer") { "Token for $dataType is missing, requesting full resync" }
                    return HealthChangeSyncOutcome.fullResync("Missing change token for $dataType")
                }

                applyChangesForType(
                    dataType = dataType,
                    token = token,
                    deviceByType = deviceByType,
                    zoneId = zoneId,
                    prefs = prefs,
                    affectedDates = affectedDates,
                    nextTokens = nextTokens,
                    budget = budget,
                )?.let { return it }
            }

            // OD-4: Track distance/elevation interval corrections independently
            syncIntervalChanges(
                grantedPermissions = grantedPermissions,
                zoneId = zoneId,
                affectedDates = affectedDates,
                budget = budget,
            )?.let { return it }

            return HealthChangeSyncOutcome(
                affectedDates = affectedDates,
                requiresFullResync = false,
                nextTokens = nextTokens,
            )
        }

        private suspend fun applyChangesForType(
            dataType: HealthDataType,
            token: String,
            deviceByType: Map<String, String>,
            zoneId: java.time.ZoneId,
            prefs: app.readylytics.health.core.model.data.preferences.UserPreferences,
            affectedDates: MutableSet<java.time.LocalDate>,
            nextTokens: MutableMap<HealthDataType, String>,
            budget: SyncBudget,
        ): HealthChangeSyncOutcome? =
            try {
                var currentToken: String = token
                var hasMore = true
                while (hasMore) {
                    if (budget.isExhausted()) {
                        return HealthChangeSyncOutcome(
                            requiresFullResync = true,
                            continuationRequired = true,
                            nextTokens = nextTokens,
                            affectedDates = affectedDates,
                            fullResyncReason = "Budget exhausted",
                        )
                    }
                    val response = client.getChanges(currentToken)
                    if (response.changesTokenExpired) {
                        logD("HealthChangeSynchronizer") {
                            "Token for $dataType is expired, requesting full resync"
                        }
                        return HealthChangeSyncOutcome.fullResync("Change token expired for $dataType")
                    }

                    val selectedDevice = deviceByType[dataType.name]?.takeIf { it.isNotBlank() }
                    val preparedWorkouts = preparedWorkoutsFor(dataType, response.changes)

                    // Apply this page of changes in a transaction
                    transactionRunner.runInTransaction {
                        processChangesPage(
                            dataType = dataType,
                            changes = response.changes,
                            affectedDates = affectedDates,
                            selectedDevice = selectedDevice,
                            zoneId = zoneId,
                            prefs = prefs,
                            preparedWorkouts = preparedWorkouts,
                        )
                    }

                    // Return candidate token only after Room transaction succeeds. The sync
                    // coordinator persists candidates after derived summaries are durable.
                    budget.pagesApplied++
                    currentToken = response.nextChangesToken
                    nextTokens[dataType] = currentToken
                    hasMore = response.hasMore
                }
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SecurityException) {
                skipTypeKeepingToken(dataType, e)
            } catch (e: Exception) {
                if (e.asHealthConnectSecurityCause() != null) {
                    skipTypeKeepingToken(dataType, e)
                } else if (isTokenExpiredException(e)) {
                    logI("HealthChangeSynchronizer") {
                        "Change token expired for $dataType"
                    }
                    HealthChangeSyncOutcome.fullResync("Change token expired for $dataType")
                } else {
                    throw e
                }
            }

        /**
         * [applyPendingChanges] only reaches a type whose read permission is still listed as
         * granted, so a `SecurityException` from `getChanges` here is transient -- e.g. Health
         * Connect refusing a read while the app is in the background. Skip the type for this run
         * and keep its token: suspending would delete the token, and the next sync would find it
         * missing and escalate to a full historical resync. A real revocation is caught by the
         * granted-permission check on the next run, which suspends the type then.
         */
        private fun skipTypeKeepingToken(
            dataType: HealthDataType,
            e: Exception,
        ): HealthChangeSyncOutcome? {
            logE("HealthChangeSynchronizer", e) {
                "SecurityException reading changes for $dataType while granted: skipping this run, keeping token"
            }
            return null
        }

        override suspend fun commitTokens(tokens: Map<HealthDataType, String>) {
            if (tokens.isNotEmpty()) {
                tokenStore.putAll(tokens, clock.millis())
            }
            if (stagedIntervalTokens.isNotEmpty()) {
                stagedIntervalTokens.forEach { (typeKey, token) ->
                    tokenStore.putToken(typeKey, token, clock.millis())
                }
                stagedIntervalTokens.clear()
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
                        "Changes token skipped for $dataType: permission not granted (${e.message})"
                    }
                    tokenStore.suspendType(dataType)
                    null
                }
            }.toMap()

        private suspend fun syncIntervalChanges(
            @Suppress("UnusedParameter")
            grantedPermissions: Set<String>,
            zoneId: java.time.ZoneId,
            affectedDates: MutableSet<java.time.LocalDate>,
            budget: SyncBudget,
        ): HealthChangeSyncOutcome? {
            for (intervalType in listOf(IngestionTokenType.DISTANCE, IngestionTokenType.ELEVATION_GAINED)) {
                val outcome = syncSingleIntervalType(intervalType, grantedPermissions, zoneId, affectedDates)
                if (outcome != null) return outcome
            }
            return null
        }

        private suspend fun syncSingleIntervalType(
            intervalType: IngestionTokenType,
            grantedPermissions: Set<String>,
            zoneId: ZoneId,
            affectedDates: MutableSet<LocalDate>,
        ): HealthChangeSyncOutcome? {
            val typePermissions = recordClassesFor(intervalType).map { HealthPermission.getReadPermission(it) }
            val isGranted = typePermissions.all { it in grantedPermissions }
            val storedToken = tokenStore.getToken(intervalType.tokenKey)

            if (!isGranted) {
                if (!storedToken.isNullOrBlank()) {
                    logD("HealthChangeSynchronizer") {
                        "Permission revoked for ${intervalType.tokenKey}: suspending token"
                    }
                    tokenStore.suspendToken(intervalType.tokenKey)
                }
                logD("HealthChangeSynchronizer") { "Skipping ${intervalType.tokenKey}: permission not granted" }
                return null
            }

            val token = storedToken ?: bootstrapIntervalToken(intervalType)
            return if (token != null) {
                applyChangesForIntervalType(
                    tokenType = intervalType,
                    token = token,
                    zoneId = zoneId,
                    affectedDates = affectedDates,
                    nextIntervalTokens = stagedIntervalTokens,
                    budget = budget,
                )
            } else {
                null
            }
        }

        private suspend fun bootstrapIntervalToken(intervalType: IngestionTokenType): String? =
            try {
                val initialToken =
                    client.getChangesToken(
                        ChangesTokenRequest(recordTypes = recordClassesFor(intervalType)),
                    )
                stagedIntervalTokens[intervalType.tokenKey] = initialToken
                initialToken
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.asHealthConnectSecurityCause() != null) {
                    tokenStore.suspendToken(intervalType.tokenKey)
                    null
                } else {
                    throw e
                }
            }

        private suspend fun applyChangesForIntervalType(
            tokenType: IngestionTokenType,
            token: String,
            zoneId: ZoneId,
            affectedDates: MutableSet<LocalDate>,
            nextIntervalTokens: MutableMap<String, String>,
        ): HealthChangeSyncOutcome? =
            try {
                var currentToken: String = token
                var hasMore = true
                val intervalKind =
                    when (tokenType) {
                        IngestionTokenType.DISTANCE -> IntervalKind.DISTANCE
                        IngestionTokenType.ELEVATION_GAINED -> IntervalKind.ELEVATION_GAINED
                        else -> error("Unsupported interval type: $tokenType")
                    }
                while (hasMore) {
                    if (budget.isExhausted()) {
                        return HealthChangeSyncOutcome(
                            requiresFullResync = true,
                            continuationRequired = true,
                            nextTokens = nextTokens,
                            affectedDates = affectedDates,
                            fullResyncReason = "Budget exhausted",
                        )
                    }
                    val response = client.getChanges(currentToken)
                    if (response.changesTokenExpired) {
                        logD("HealthChangeSynchronizer") {
                            "Token for ${tokenType.tokenKey} is expired, requesting full resync"
                        }
                        return HealthChangeSyncOutcome.fullResync("Change token expired for ${tokenType.tokenKey}")
                    }

                    val intervalChanges = response.changes
                        .mapNotNull { toIntervalChange(it, intervalKind) }
                    if (intervalChanges.isNotEmpty()) {
                        val dates = workoutEnrichmentRefresher.refreshForIntervalChanges(intervalChanges, zoneId)
                        affectedDates.addAll(dates)
                    }

                    budget.pagesApplied++
                    budget.pagesApplied++
                    currentToken = response.nextChangesToken
                    nextIntervalTokens[tokenType.tokenKey] = currentToken
                    hasMore = response.hasMore
                }
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SecurityException) {
                logE("HealthChangeSynchronizer", e) {
                    "SecurityException reading changes for ${tokenType.tokenKey}: suspending token"
                }
                tokenStore.suspendToken(tokenType.tokenKey)
                null
            } catch (e: Exception) {
                if (e.asHealthConnectSecurityCause() != null) {
                    logE("HealthChangeSynchronizer", e) {
                        "SecurityException reading changes for ${tokenType.tokenKey}: suspending token"
                    }
                    tokenStore.suspendToken(tokenType.tokenKey)
                    null
                } else if (isTokenExpiredException(e)) {
                    logD("HealthChangeSynchronizer") {
                        "Change token expired for ${tokenType.tokenKey}"
                    }
                    HealthChangeSyncOutcome.fullResync("Change token expired for ${tokenType.tokenKey}")
                } else {
                    throw e
                }
            }

        private suspend fun toIntervalChange(
            change: Change,
            fallbackKind: IntervalKind,
        ): IntervalChange? =
            when (change) {
                is UpsertionChange -> toIntervalUpsertion(change.record)
                is DeletionChange -> toIntervalDeletion(change.recordId, fallbackKind)
                else -> null
            }

        private suspend fun toIntervalUpsertion(record: Record): IntervalChange? =
            when (record) {
                is DistanceRecord -> {
                    val oldSource = changeIngestionStore.getIntervalSource(record.metadata.id)
                    IntervalChange(
                        sourceId = record.metadata.id,
                        kind = IntervalKind.DISTANCE,
                        oldStartMs = oldSource?.startMs,
                        oldEndExclusiveMs = oldSource?.endExclusiveMs,
                        newStartMs = record.startTime.toEpochMilli(),
                        newEndExclusiveMs = record.endTime.toEpochMilli(),
                        originPackage = record.metadata.dataOrigin.packageName,
                    )
                }
                is ElevationGainedRecord -> {
                    val oldSource = changeIngestionStore.getIntervalSource(record.metadata.id)
                    IntervalChange(
                        sourceId = record.metadata.id,
                        kind = IntervalKind.ELEVATION_GAINED,
                        oldStartMs = oldSource?.startMs,
                        oldEndExclusiveMs = oldSource?.endExclusiveMs,
                        newStartMs = record.startTime.toEpochMilli(),
                        newEndExclusiveMs = record.endTime.toEpochMilli(),
                        originPackage = record.metadata.dataOrigin.packageName,
                    )
                }
                else -> null
            }

        private suspend fun toIntervalDeletion(
            recordId: String,
            fallbackKind: IntervalKind,
        ): IntervalChange {
            val oldSource = changeIngestionStore.getIntervalSource(recordId)
            return IntervalChange(
                sourceId = recordId,
                kind = fallbackKind,
                oldStartMs = oldSource?.startMs,
                oldEndExclusiveMs = oldSource?.endExclusiveMs,
                newStartMs = null,
                newEndExclusiveMs = null,
            )
        }

        private suspend fun processChangesPage(
            dataType: HealthDataType,
            changes: List<androidx.health.connect.client.changes.Change>,
            affectedDates: MutableSet<java.time.LocalDate>,
            selectedDevice: String?,
            zoneId: java.time.ZoneId,
            prefs: app.readylytics.health.core.model.data.preferences.UserPreferences,
            preparedWorkouts: Map<String, app.readylytics.health.core.model.domain.sync.PreparedWorkout>,
        ) {
            val spans = pageSessionSpans(dataType, changes)
            val allIds = changes.mapNotNull {
                when (it) {
                    is androidx.health.connect.client.changes.UpsertionChange -> it.record.metadata.id
                    is androidx.health.connect.client.changes.DeletionChange -> it.recordId
                    else -> null
                }
            }
            affectedDates.addAll(changeIngestionStore.affectedDatesForRecords(dataType, allIds, zoneId))
            val toDeleteIds = mutableListOf<String>()
            val toUpsert = mutableListOf<androidx.health.connect.client.records.Record>()
            
            for (change in changes) {
                when (change) {
                    is androidx.health.connect.client.changes.UpsertionChange -> {
                        val record = change.record
                        val deviceLabel = app.readylytics.health.core.model.domain.model.DeviceLabel.from(
                            record.metadata.device, record.metadata.dataOrigin
                        )
                        val keep = selectedDevice == null || deviceLabel == selectedDevice
                        if (!keep || dataType != HealthDataType.EXERCISE) {
                            toDeleteIds.add(record.metadata.id)
                        }
                        if (keep) {
                            affectedDates.addAll(getDatesForRecord(record, zoneId))
                            toUpsert.add(record)
                        }
                    }
                    is androidx.health.connect.client.changes.DeletionChange -> {
                        toDeleteIds.add(change.recordId)
                    }
                }
            }
            changeIngestionStore.deleteRecords(dataType, toDeleteIds)
            if (toUpsert.isNotEmpty()) {
                upsertRecords(dataType, toUpsert, prefs, spans, preparedWorkouts, healthIngestionStore, changeIngestionStore)
            }
        }
                        if (keep) {
                            affectedDates.addAll(getDatesForRecord(record, zoneId))
                            upsertRecord(dataType, record, prefs, spans, preparedWorkouts)
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
         * H5/WP-09: every Health Connect SDK read an EXERCISE upsertion needs (route consent,
         * distance/elevation interval totals) is resolved here, BEFORE the writer transaction
         * opens -- never inside it. No-op for every other data type.
         */
        private suspend fun preparedWorkoutsFor(
            dataType: HealthDataType,
            changes: List<Change>,
        ): Map<String, PreparedWorkout> =
            if (dataType == HealthDataType.EXERCISE) prepareWorkouts(changes) else emptyMap()

        /**
         * Resolves every Health Connect SDK read this page's EXERCISE upsertions need (route
         * consent, distance/elevation interval totals) up front, keyed by HC record id, so
         * `processChangesPage`'s writer transaction only ever touches already-resolved values.
         */
        private suspend fun prepareWorkouts(changes: List<Change>): Map<String, PreparedWorkout> =
            changes.filterIsInstance<UpsertionChange>()
                .mapNotNull { it.record as? ExerciseSessionRecord }
                .associate { record ->
                    val durationMinutes =
                        ((record.endTime.toEpochMilli() - record.startTime.toEpochMilli()) / 60_000L).toInt()
                    val baseWorkout =
                        WorkoutInput(
                            id = record.metadata.id,
                            startTime = record.startTime.toEpochMilli(),
                            endTime = record.endTime.toEpochMilli(),
                            exerciseType = record.exerciseType.toString(),
                            durationMinutes = durationMinutes,
                            zone1Minutes = 0f,
                            zone2Minutes = 0f,
                            zone3Minutes = 0f,
                            zone4Minutes = 0f,
                            zone5Minutes = 0f,
                            trimp = 0f,
                            avgHr = 0f,
                            deviceName = DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin),
                        )
                    record.metadata.id to workoutReadPreparer.prepare(record, baseWorkout)
                }

        /**
         * R2-HC-003: one `sessionSpansOverlapping` call for the whole page's time range, instead of
         * one per HEART_RATE/HRV record. Only fetched for the two data types that consume spans.
         */
        private suspend fun pageSessionSpans(dataType: HealthDataType, changes: List<Change>): SessionSpans {
            val spanConsumingTypes = setOf(HealthDataType.HEART_RATE, HealthDataType.HRV)
            // Health Connect's per-type change token guarantees a HEART_RATE/HRV page never
            // contains another record type, but this filters defensively via mapNotNull instead
            // of erroring on a type mismatch -- one unexpected record must skip cleanly, never
            // abort applyPendingChanges() for every data type (see the surrounding try/catch that
            // re-throws non-token-expiry exceptions).
            val ranges =
                if (dataType in spanConsumingTypes) {
                    changes.filterIsInstance<UpsertionChange>().mapNotNull { recordTimeRangeMs(it.record) }
                } else {
                    emptyList()
                }
            if (ranges.isEmpty()) return SessionSpans(emptyList(), emptyList())
            return changeIngestionStore.sessionSpansOverlapping(
                ranges.minOf { it.first },
                ranges.maxOf { it.second },
            )
        }

        private fun recordTimeRangeMs(record: Record): Pair<Long, Long>? =
            when (record) {
                is HealthConnectHeartRateRecord -> record.startTime.toEpochMilli() to record.endTime.toEpochMilli()
                is HeartRateVariabilityRmssdRecord -> record.time.toEpochMilli().let { it to it }
                else -> null
            }

}
