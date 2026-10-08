package app.readylytics.health.core.model.domain.util

import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.sync.ResyncPhase

/**
 * SEC-101 / OD-1: the only structured context a release diagnostic may carry. Every field is a
 * bounded enum, a coarse [CountBucket], or a day offset relative to today — never a timestamp,
 * absolute date, record id, device/origin name, health value or raw count. DiagnosticFieldsTest
 * fails if a field of any other type is added: widening this class is a privacy decision
 * (update docs/privacy.md), not a refactor.
 */
data class DiagnosticFields(
    val dataType: HealthDataType? = null,
    val phase: ResyncPhase? = null,
    /** Affected day minus today in the scoring zone: -3 means three days ago. */
    val dayOffsetFromToday: Int? = null,
    val recordCount: CountBucket? = null,
) {
    /** Space-separated `key=value` pairs for the non-null fields, by enum name; empty when none. */
    fun render(): String =
        listOfNotNull(
            dataType?.let { "dataType=${it.name}" },
            phase?.let { "phase=${it.name}" },
            dayOffsetFromToday?.let { "dayOffset=$it" },
            recordCount?.let { "records=${it.name}" },
        ).joinToString(" ")
}

/** Coarse record-count ranges: none, 1–10, 11–100, more than 100. */
enum class CountBucket {
    NONE,
    FEW,
    DOZENS,
    MANY,
    ;

    companion object {
        private const val FEW_MAX = 10
        private const val DOZENS_MAX = 100

        fun of(count: Int): CountBucket =
            when {
                count <= 0 -> NONE
                count <= FEW_MAX -> FEW
                count <= DOZENS_MAX -> DOZENS
                else -> MANY
            }
    }
}
