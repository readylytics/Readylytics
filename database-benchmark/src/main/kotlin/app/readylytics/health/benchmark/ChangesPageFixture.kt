package app.readylytics.health.benchmark

import app.readylytics.health.core.model.domain.model.DomainHeartRateRecord

/**
 * The `CHANGES-1K` fixture from §11 of the remediation plan: 1,000 upsertions and 200 deletions.
 *
 * Phase 0 uses it to size OD-6's `DEFAULT_CHANGES_APPLY_BUDGET_MS` by measuring the **per-record
 * write cost** that `HealthChangeSynchronizerImpl.processChangesPage` pays once per change (HC-103).
 * It does NOT drive `HealthChangeSynchronizerImpl` itself — that needs a `HealthConnectClient` this
 * module does not depend on — so the recorded number is a floor on the real phase cost, not the whole
 * of it. `benchmark/BASELINE.md` must say so wherever the number appears.
 */
object ChangesPageFixture {
    const val UPSERTION_COUNT: Int = 1_000
    const val DELETION_COUNT: Int = 200

    fun upsertionRecords(count: Int = UPSERTION_COUNT): List<DomainHeartRateRecord> =
        HealthParentFixture.pages(parentCount = count, samplesPerParent = 1, pageSize = count).first()

    fun deletionIds(count: Int = DELETION_COUNT): List<String> =
        upsertionRecords(UPSERTION_COUNT).take(count).map { it.id }
}
