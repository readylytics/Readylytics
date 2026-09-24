package app.readylytics.health.core.model.domain.sync

import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.util.RetentionBounds
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Immutable clock, timezone, and retention boundaries shared by one scoring or maintenance run. */
data class ScoringRunContext(
    val instant: Instant,
    val historicalWindow: RetentionBounds.HistoricalWindow,
    val sourceGeneration: Long = 0L,
    val scoringSnapshotId: String = "",
) {
    val zoneId: ZoneId
        get() = historicalWindow.zoneId

    val today: LocalDate
        get() = historicalWindow.endDate

    val startDate: LocalDate
        get() = historicalWindow.startDate

    val retentionStartMs: Long
        get() = historicalWindow.startTimeMs

    companion object {
        fun capture(
            prefs: UserPreferences,
            instant: Instant,
            sourceGeneration: Long = 0L,
            scoringSnapshotId: String = "",
        ): ScoringRunContext =
            ScoringRunContext(
                instant = instant,
                historicalWindow = RetentionBounds.resolveHistoricalWindow(prefs, instant),
                sourceGeneration = sourceGeneration,
                scoringSnapshotId = scoringSnapshotId,
            )
    }
}
