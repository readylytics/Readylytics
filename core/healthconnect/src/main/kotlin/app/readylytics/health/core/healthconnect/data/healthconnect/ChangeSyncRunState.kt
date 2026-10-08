package app.readylytics.health.core.healthconnect.data.healthconnect

import app.readylytics.health.core.healthconnect.domain.sync.DEFAULT_CHANGES_APPLY_BUDGET_MS
import app.readylytics.health.core.healthconnect.domain.sync.HealthChangeSyncOutcome
import app.readylytics.health.core.healthconnect.domain.sync.MAX_CHANGE_PAGES_PER_RUN
import app.readylytics.health.core.model.domain.model.HealthDataType
import java.time.LocalDate

/** HC-103 time + page budget shared by every typed and interval changes page in one sync run. */
internal class ChangeSyncBudget {
    private val startNanos = System.nanoTime()
    var pagesApplied = 0

    fun isExhausted(): Boolean {
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L
        return elapsedMs >= DEFAULT_CHANGES_APPLY_BUDGET_MS || pagesApplied >= MAX_CHANGE_PAGES_PER_RUN
    }
}

/**
 * Mutable state threaded through one `applyPendingChanges` run: affected dates/candidate tokens
 * collected so far across every data type and interval kind, and the budget they all share.
 * Bundled into one holder so the page-apply functions stay under detekt's LongParameterList.
 */
internal class ChangeSyncRunState(
    val affectedDates: MutableSet<LocalDate> = mutableSetOf(),
    val nextTokens: MutableMap<HealthDataType, String> = mutableMapOf(),
    val budget: ChangeSyncBudget = ChangeSyncBudget(),
    val nextIntervalTokens: MutableMap<String, String> = mutableMapOf(),
) {
    fun budgetExhaustedOutcome(): HealthChangeSyncOutcome =
        HealthChangeSyncOutcome(
            requiresFullResync = true,
            continuationRequired = true,
            nextTokens = nextTokens.toMap(),
            nextIntervalTokens = nextIntervalTokens.toMap(),
            affectedDates = affectedDates,
            fullResyncReason = "Budget exhausted",
        )

    fun completedOutcome(): HealthChangeSyncOutcome =
        HealthChangeSyncOutcome(
            affectedDates = affectedDates,
            requiresFullResync = false,
            nextTokens = nextTokens.toMap(),
            nextIntervalTokens = nextIntervalTokens.toMap(),
        )
}
