package app.readylytics.health.core.model.domain.sync

import java.time.LocalDate

/**
 * R2-HC-004: one rule for both sync flows. Previously the daily sync always preserved the
 * stored step count on missing data, while the resync wrote 0 when a step device was selected
 * — the same day scored differently depending on which flow last ran. OD-2 (2026-08-31):
 * "no step data for a selected device" means 0 steps, not "preserve" — the resync is the
 * authority that can correct a stale stored value.
 */
object StepAttribution {
    /** `null` means "preserve the stored count"; a value means "overwrite it". */
    fun resolve(
        day: LocalDate,
        steps: Map<LocalDate, Long>,
        stepsDeviceSelected: Boolean,
        recomputeOnly: Boolean,
    ): Long? =
        when {
            recomputeOnly -> null
            stepsDeviceSelected -> steps[day] ?: 0L
            else -> steps[day]
        }
}
