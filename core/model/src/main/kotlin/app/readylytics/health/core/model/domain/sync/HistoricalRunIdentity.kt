package app.readylytics.health.core.model.domain.sync

import app.readylytics.health.core.model.domain.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Serializable
data class HistoricalRunIdentity(
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
    val runId: String,
    val mode: String,
    val startEpochDay: Long,
    val endEpochDayInclusive: Long,
    val zoneId: String,
    val startedAtEpochMs: Long,
    val sourceSelectionId: String,
    val algorithmRevision: Int,
    val scoringSnapshotJson: String,
    val scoringSnapshotId: String,
) {
    fun decodeScoringSnapshot(): ScoringRunSnapshot? =
        runCatching {
            Json.decodeFromString<ScoringRunSnapshot>(scoringSnapshotJson)
        }.getOrNull()

    fun effectivePreferences(): UserPreferences? = decodeScoringSnapshot()?.toPreferencesOrNull()

    companion object {
        const val CURRENT_PROTOCOL_VERSION: Int = 2
        const val FULL_INGEST: String = "FULL_INGEST"
        const val RECOMPUTE_ONLY: String = "RECOMPUTE_ONLY"
        const val MODE_FULL_INGEST: String = FULL_INGEST
        const val MODE_RECOMPUTE_ONLY: String = RECOMPUTE_ONLY

        fun create(
            runId: String = UUID.randomUUID().toString(),
            mode: String,
            startDate: LocalDate,
            endDate: LocalDate,
            zoneId: ZoneId,
            prefs: UserPreferences,
            resolvedHrMax: Float,
            startedAtEpochMs: Long,
            algorithmRevision: Int = SettingsDefaults.CURRENT_SCORING_VERSION,
        ): HistoricalRunIdentity {
            val snapshot = ScoringRunSnapshot.capture(prefs, resolvedHrMax)
            val snapshotJson = Json.encodeToString(snapshot)
            val snapshotId = sha256Hex(snapshotJson)
            val sourceSelectionId =
                sha256Hex(
                    snapshot.sourceSelection.entries
                        .sortedBy { it.key }
                        .joinToString("|") { "${it.key}=${it.value}" },
                )
            return HistoricalRunIdentity(
                protocolVersion = CURRENT_PROTOCOL_VERSION,
                runId = runId,
                mode = mode,
                startEpochDay = startDate.toEpochDay(),
                endEpochDayInclusive = endDate.toEpochDay(),
                zoneId = zoneId.id,
                startedAtEpochMs = startedAtEpochMs,
                sourceSelectionId = sourceSelectionId,
                algorithmRevision = algorithmRevision,
                scoringSnapshotJson = snapshotJson,
                scoringSnapshotId = snapshotId,
            )
        }

        fun computeSnapshotId(prefs: UserPreferences, resolvedHrMax: Float): String {
            val snapshot = ScoringRunSnapshot.capture(prefs, resolvedHrMax)
            val snapshotJson = Json.encodeToString(snapshot)
            return sha256Hex(snapshotJson)
        }

        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}
