package app.readylytics.health.core.model.domain.crashreport

import app.readylytics.health.core.model.domain.sync.DirtyTicket
import app.readylytics.health.core.model.domain.sync.RecalcTrigger
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * One large background recalculation, captured so its cause can be shared from the startup
 * report dialog. Carries only dates, counts and trigger names -- never health values, matching
 * [CrashReportMetadata]'s policy.
 */
data class RecalcDiagnostic(
    val timestampIso: String,
    val appVersionName: String,
    val appVersionCode: Long,
    val trigger: RecalcTrigger,
    val triggerDetail: String?,
    val recomputeOnly: Boolean,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val pendingTickets: List<DirtyTicket> = emptyList(),
) {
    val dayCount: Long get() = ChronoUnit.DAYS.between(startDate, endDate) + 1
}

/** A normal background sync recomputes 2-3 days; anything longer from an unexpected trigger is reported. */
const val LARGE_RECALC_THRESHOLD_DAYS = 3L

/** How many recalculation entries the diagnostic log keeps (oldest dropped first). */
const val MAX_RECALC_DIAGNOSTIC_ENTRIES = 20

private const val ENTRY_SEPARATOR = "\n----\n"
private const val MAX_LISTED_TICKETS = 20

fun shouldRecordRecalcDiagnostic(
    trigger: RecalcTrigger,
    startDate: LocalDate,
    endDate: LocalDate,
): Boolean = trigger.isUnexpected && ChronoUnit.DAYS.between(startDate, endDate) + 1 > LARGE_RECALC_THRESHOLD_DAYS

fun formatRecalcDiagnostic(diagnostic: RecalcDiagnostic): String =
    buildString {
        appendLine("Large background recalculation")
        appendLine("Time: ${diagnostic.timestampIso}")
        appendLine("App version: ${diagnostic.appVersionName} (${diagnostic.appVersionCode})")
        appendLine("Trigger: ${diagnostic.trigger.name}")
        diagnostic.triggerDetail?.takeIf { it.isNotBlank() }?.let { appendLine("Detail: $it") }
        appendLine("Mode: ${if (diagnostic.recomputeOnly) "recompute-only" else "full Health Connect resync"}")
        appendLine("Range: ${diagnostic.startDate}..${diagnostic.endDate} (${diagnostic.dayCount} days)")
        appendLine("Pending dirty tickets: ${diagnostic.pendingTickets.size}")
        diagnostic.pendingTickets.take(MAX_LISTED_TICKETS).forEach { ticket ->
            appendLine(
                "  #${ticket.id} ${ticket.reason.ifBlank { "?" }} next=${ticket.nextDay} " +
                    "end=${ticket.endInclusive} gen=${ticket.sourceGeneration}",
            )
        }
    }.trimEnd()

/**
 * Appends [entry] to the [existing] diagnostic log, keeping only the newest [maxEntries] entries so
 * repeated nightly events accumulate into one bounded report.
 */
fun appendRecalcDiagnosticEntry(
    existing: String?,
    entry: String,
    maxEntries: Int = MAX_RECALC_DIAGNOSTIC_ENTRIES,
): String {
    val entries = existing?.split(ENTRY_SEPARATOR)?.filter { it.isNotBlank() }.orEmpty() + entry
    return entries.takeLast(maxEntries).joinToString(ENTRY_SEPARATOR)
}
