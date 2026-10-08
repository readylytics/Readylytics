package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.Change
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.BloodPressureRecord as HealthConnectBloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord as HealthConnectBodyFatRecord
import androidx.health.connect.client.records.WeightRecord as HealthConnectWeightRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.permission.HealthPermission
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.heartrate.ZoneThresholds
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.data.preferences.scoringZone
import app.readylytics.health.core.model.domain.model.*
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.healthconnect.domain.sync.CapturedChangeTokens
import app.readylytics.health.core.healthconnect.domain.sync.HealthChangeSyncOutcome
import app.readylytics.health.core.healthconnect.domain.sync.HealthChangeSynchronizer
import app.readylytics.health.core.model.domain.sync.*
import app.readylytics.health.core.model.domain.sync.mappers.*
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.model.domain.util.logI
import kotlinx.coroutines.flow.first
import java.time.Clock
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
    private val intervalChangeSync =
        IntervalChangeSync(client, tokenStore, changeIngestionStore, workoutEnrichmentRefresher)

    override suspend fun applyPendingChanges(): HealthChangeSyncOutcome {
            val prefs = settingsRepo.userPreferences.first()
            val zoneId = prefs.scoringZone()
            val state = ChangeSyncRunState()

            // A failed lookup must fail this sync (the caller retries), never read as "nothing
            // granted": that would suspend -- i.e. delete -- every change token, and the next sync
            // would find them missing and escalate to a full historical resync.
            val grantedPermissions: Set<String> = client.permissionController.getGrantedPermissions()

            return HealthDataType.entries.firstNotNullOfOrNull {
                applyTypeIfGranted(it, grantedPermissions, prefs, zoneId, state)
            }
                // OD-4: Track distance/elevation interval corrections independently
                ?: intervalChangeSync.sync(grantedPermissions, zoneId, state)
                ?: state.completedOutcome()
        }

        private suspend fun applyTypeIfGranted(
            dataType: HealthDataType,
            grantedPermissions: Set<String>,
            prefs: UserPreferences,
            zoneId: ZoneId,
            state: ChangeSyncRunState,
        ): HealthChangeSyncOutcome? {
            val isGranted =
                recordClassesFor(dataType).all { HealthPermission.getReadPermission(it) in grantedPermissions }
            val token = tokenStore.get(dataType)
            return when {
                !isGranted -> {
                    if (!token.isNullOrBlank()) {
                        logI("HealthChangeSynchronizer") { "Permission revoked for $dataType: suspending token" }
                        tokenStore.suspendType(dataType)
                    }
                    logD("HealthChangeSynchronizer") { "Skipping $dataType: permission not granted" }
                    null
                }
                token.isNullOrBlank() -> {
                    logI("HealthChangeSynchronizer") { "Token for $dataType is missing, requesting full resync" }
                    HealthChangeSyncOutcome.fullResync("Missing change token for $dataType")
                }
                else -> applyChangesForType(dataType, token, prefs.deviceByDataType, zoneId, prefs, state)
            }
        }

        private suspend fun applyChangesForType(
            dataType: HealthDataType,
            token: String,
            deviceByType: Map<String, String>,
            zoneId: ZoneId,
            prefs: UserPreferences,
            state: ChangeSyncRunState,
        ): HealthChangeSyncOutcome? =
            try {
                var currentToken: String = token
                var hasMore = true
                while (hasMore) {
                    if (state.budget.isExhausted()) return state.budgetExhaustedOutcome()
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
                            affectedDates = state.affectedDates,
                            selectedDevice = selectedDevice,
                            zoneId = zoneId,
                            prefs = prefs,
                            preparedWorkouts = preparedWorkouts,
                        )
                    }

                    // Return candidate token only after Room transaction succeeds. The sync
                    // coordinator persists candidates after derived summaries are durable.
                    state.budget.pagesApplied++
                    currentToken = response.nextChangesToken
                    state.nextTokens[dataType] = currentToken
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

        override suspend fun commitTokens(typed: Map<HealthDataType, String>, intervals: Map<String, String>) {
            if (typed.isNotEmpty()) {
                tokenStore.putAll(typed, clock.millis())
            }
            if (intervals.isNotEmpty()) {
                val granted = client.permissionController.getGrantedPermissions()
                for (type in listOf(IngestionTokenType.DISTANCE, IngestionTokenType.ELEVATION_GAINED)) {
                    val token = intervals[type.tokenKey] ?: continue
                    if (recordClassesFor(type).all { HealthPermission.getReadPermission(it) in granted }) {
                        tokenStore.putToken(type.tokenKey, token, clock.millis())
                    }
                }
            }
        }

        // Optional data types (weight, body fat, BP, SpO2, body temperature, steps) may lack
        // permission -- a permission-denied getChangesToken call must not abort the whole resync,
        // it just means that type gets no baseline token (mirrors the read-side degrade pattern).
        private suspend fun captureTypedTokens(): Map<HealthDataType, String> =
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

        override suspend fun captureChangesTokens(): CapturedChangeTokens {
            val typed = captureTypedTokens()
            val intervals = listOf(IngestionTokenType.DISTANCE, IngestionTokenType.ELEVATION_GAINED)
                .mapNotNull { type ->
                    intervalChangeSync.bootstrapToken(type, suspendOnDenial = true)?.let { type.tokenKey to it }
                }.toMap()
            return CapturedChangeTokens(typed, intervals)
        }

        private suspend fun processChangesPage(
            dataType: HealthDataType,
            changes: List<Change>,
            affectedDates: MutableSet<java.time.LocalDate>,
            selectedDevice: String?,
            zoneId: java.time.ZoneId,
            prefs: app.readylytics.health.core.model.data.preferences.UserPreferences,
            preparedWorkouts: Map<String, PreparedWorkout>,
        ) {
            val spans = changeIngestionStore.pageSessionSpans(dataType, changes)
            val lastEvents = lastEventPerId(changes)
            affectedDates.addAll(
                changeIngestionStore.affectedDatesForRecords(dataType, lastEvents.keys.toList(), zoneId),
            )
            val toDeleteIds = mutableListOf<String>()
            val toUpsert = mutableListOf<Record>()
            for (change in lastEvents.values) {
                when (change) {
                    is UpsertionChange -> {
                        val record = change.record
                        val deviceLabel = DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin)
                        val keep = selectedDevice == null || deviceLabel == selectedDevice
                        if (!keep || dataType !in REPLACED_WITHOUT_PRE_DELETE) {
                            toDeleteIds.add(record.metadata.id)
                        }
                        if (keep) {
                            affectedDates.addAll(getDatesForRecord(record, zoneId))
                            toUpsert.add(record)
                        }
                    }
                    is DeletionChange -> {
                        toDeleteIds.add(change.recordId)
                    }
                    else -> Unit
                }
            }
            if (toDeleteIds.isNotEmpty()) {
                changeIngestionStore.deleteRecords(dataType, toDeleteIds)
            }
            if (toUpsert.isNotEmpty()) {
                upsertRecords(
                    dataType, toUpsert, prefs, spans, preparedWorkouts, healthIngestionStore, changeIngestionStore,
                )
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

}

/**
 * Kept upsertions of these types skip the pre-delete: `EXERCISE` updates in place (keeps
 * `modelTrimp` and the coalesced route), and `HEART_RATE`/`HRV` go through the source-scoped
 * `replaceHeartRateSources`/`replaceHrvSources`, which already drop rows the new payload omits,
 * journal exactly one dirty ticket, and skip an identical re-report entirely. A pre-delete would
 * journal a second ticket and defeat that skip. De-selected records are still deleted.
 */
private val REPLACED_WITHOUT_PRE_DELETE =
    setOf(HealthDataType.EXERCISE, HealthDataType.HEART_RATE, HealthDataType.HRV)
