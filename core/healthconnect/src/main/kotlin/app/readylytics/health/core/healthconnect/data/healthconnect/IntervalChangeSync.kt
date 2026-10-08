package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.Change
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ChangesTokenRequest
import app.readylytics.health.core.healthconnect.domain.sync.HealthChangeSyncOutcome
import app.readylytics.health.core.model.domain.sync.*
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.model.domain.util.logE
import java.time.ZoneId

/**
 * OD-4: distance/elevation interval corrections, tracked with their own Changes tokens and
 * applied through [WorkoutEnrichmentRefresher]. Shares the caller's [ChangeSyncRunState] so the
 * HC-103 budget and candidate-token bookkeeping stay one per run.
 */
internal class IntervalChangeSync(
    private val client: HealthConnectClient,
    private val tokenStore: HealthChangeTokenStore,
    private val changeIngestionStore: HealthChangeIngestionStore,
    private val workoutEnrichmentRefresher: WorkoutEnrichmentRefresher,
) {
    suspend fun sync(
        grantedPermissions: Set<String>,
        zoneId: ZoneId,
        state: ChangeSyncRunState,
    ): HealthChangeSyncOutcome? =
        INTERVAL_TYPES.firstNotNullOfOrNull { syncSingleIntervalType(it, grantedPermissions, zoneId, state) }

    /**
     * [suspendOnDenial] is only true from the baseline-capture context (`captureChangesTokens`),
     * which has no prior permission check -- a genuine denial there must be recorded the same way
     * typed tokens record it. [sync]'s lazy fallback confirms permission moments earlier, so it
     * leaves this false: a denial there is transient, not a real revocation.
     */
    suspend fun bootstrapToken(
        intervalType: IngestionTokenType,
        suspendOnDenial: Boolean = false,
    ): String? =
        try {
            client.getChangesToken(ChangesTokenRequest(recordTypes = recordClassesFor(intervalType)))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.asHealthConnectSecurityCause() != null) {
                if (suspendOnDenial) tokenStore.suspendToken(intervalType.tokenKey)
                null
            } else {
                throw e
            }
        }

    private suspend fun syncSingleIntervalType(
        intervalType: IngestionTokenType,
        grantedPermissions: Set<String>,
        zoneId: ZoneId,
        state: ChangeSyncRunState,
    ): HealthChangeSyncOutcome? {
        val typePermissions = recordClassesFor(intervalType).map { HealthPermission.getReadPermission(it) }
        val isGranted = typePermissions.all { it in grantedPermissions }
        val storedToken = tokenStore.getToken(intervalType.tokenKey)

        if (!isGranted) {
            if (!storedToken.isNullOrBlank()) {
                logD(TAG) { "Permission revoked for ${intervalType.tokenKey}: suspending token" }
                tokenStore.suspendToken(intervalType.tokenKey)
            }
            logD(TAG) { "Skipping ${intervalType.tokenKey}: permission not granted" }
            return null
        }

        val token = storedToken ?: bootstrapToken(intervalType)
        return if (token != null) applyChangesForIntervalType(intervalType, token, zoneId, state) else null
    }

    private suspend fun applyChangesForIntervalType(
        tokenType: IngestionTokenType,
        token: String,
        zoneId: ZoneId,
        state: ChangeSyncRunState,
    ): HealthChangeSyncOutcome? =
        try {
            var currentToken: String = token
            var hasMore = true
            val intervalKind = intervalKindFor(tokenType)
            while (hasMore) {
                if (state.budget.isExhausted()) return state.budgetExhaustedOutcome()
                val response = client.getChanges(currentToken)
                if (response.changesTokenExpired) {
                    logD(TAG) { "Token for ${tokenType.tokenKey} is expired, requesting full resync" }
                    return HealthChangeSyncOutcome.fullResync("Change token expired for ${tokenType.tokenKey}")
                }

                applyIntervalChangesPage(response.changes, intervalKind, zoneId, state)

                state.budget.pagesApplied++
                currentToken = response.nextChangesToken
                state.nextIntervalTokens[tokenType.tokenKey] = currentToken
                hasMore = response.hasMore
            }
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SecurityException) {
            logE(TAG, e) { "SecurityException reading changes for ${tokenType.tokenKey}: keeping token" }
            state.nextIntervalTokens.remove(tokenType.tokenKey)
            null
        } catch (e: Exception) {
            if (e.asHealthConnectSecurityCause() != null) {
                logE(TAG, e) { "SecurityException reading changes for ${tokenType.tokenKey}: keeping token" }
                state.nextIntervalTokens.remove(tokenType.tokenKey)
                null
            } else if (isTokenExpiredException(e)) {
                logD(TAG) { "Change token expired for ${tokenType.tokenKey}" }
                HealthChangeSyncOutcome.fullResync("Change token expired for ${tokenType.tokenKey}")
            } else {
                throw e
            }
        }

    /** One page of one interval type's changes: resolves affected dates, never touches tokens/budget. */
    private suspend fun applyIntervalChangesPage(
        changes: List<Change>,
        intervalKind: IntervalKind,
        zoneId: ZoneId,
        state: ChangeSyncRunState,
    ) {
        val intervalChanges = changes.mapNotNull { toIntervalChange(it, intervalKind) }
        if (intervalChanges.isNotEmpty()) {
            state.affectedDates.addAll(workoutEnrichmentRefresher.refreshForIntervalChanges(intervalChanges, zoneId))
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
            is DistanceRecord ->
                intervalUpsertion(
                    record,
                    IntervalKind.DISTANCE,
                    record.startTime.toEpochMilli(),
                    record.endTime.toEpochMilli(),
                )
            is ElevationGainedRecord ->
                intervalUpsertion(
                    record,
                    IntervalKind.ELEVATION_GAINED,
                    record.startTime.toEpochMilli(),
                    record.endTime.toEpochMilli(),
                )
            else -> null
        }

    private suspend fun intervalUpsertion(
        record: Record,
        kind: IntervalKind,
        startMs: Long,
        endMs: Long,
    ): IntervalChange {
        val oldSource = changeIngestionStore.getIntervalSource(record.metadata.id)
        return IntervalChange(
            sourceId = record.metadata.id,
            kind = kind,
            oldStartMs = oldSource?.startMs,
            oldEndExclusiveMs = oldSource?.endExclusiveMs,
            newStartMs = startMs,
            newEndExclusiveMs = endMs,
            originPackage = record.metadata.dataOrigin.packageName,
        )
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

    private companion object {
        const val TAG = "HealthChangeSynchronizer"
        val INTERVAL_TYPES = listOf(IngestionTokenType.DISTANCE, IngestionTokenType.ELEVATION_GAINED)
    }
}

private fun intervalKindFor(tokenType: IngestionTokenType): IntervalKind =
    when (tokenType) {
        IngestionTokenType.DISTANCE -> IntervalKind.DISTANCE
        IngestionTokenType.ELEVATION_GAINED -> IntervalKind.ELEVATION_GAINED
        else -> error("Unsupported interval type: $tokenType")
    }
