package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.sync.ScanIdentity
import java.time.Instant
import java.time.LocalDate

/**
 * WP-18 scan identity factories. The daily flow is a single logical run whose chunk is the refreshed
 * window; a historical resync uses its immutable [HistoricalRunIdentity.runId] and the chunk's start
 * epoch day, so a resumed chunk reopens exactly the staging its interrupted predecessor wrote and a
 * new run can never inherit an old one's staged identities.
 */
object ScanIdentities {
    const val DAILY_RUN_ID: String = "DAILY_SYNC"

    fun daily(windowStart: Instant): ScanIdentity =
        ScanIdentity(runId = DAILY_RUN_ID, chunkId = windowStart.toEpochMilli().toString())

    fun historical(
        runId: String,
        chunkStart: LocalDate,
    ): ScanIdentity = ScanIdentity(runId = runId, chunkId = chunkStart.toEpochDay().toString())
}
