package app.readylytics.health.core.model.domain.sync

import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScoreInvalidationTest {
    @Test
    fun `affected range extends 84 days past the changed range but never past today`() {
        val changed = ScoreInvalidation.AffectedRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 10))
        val today = LocalDate.of(2026, 2, 1)
        val result = ScoreInvalidation.affectedRange(changed, today)
        assertEquals(LocalDate.of(2026, 1, 1), result.start)
        assertEquals(LocalDate.of(2026, 2, 1), result.endInclusive)
    }

    @Test
    fun `dependency closure repairs retained suffix through today across scoring reasons`() {
        val changedDay = LocalDate.of(2025, 1, 1)
        val retentionStart = LocalDate.of(2024, 1, 1)
        val today = LocalDate.of(2026, 9, 23)
        val changed = ScoreInvalidation.AffectedRange(changedDay, changedDay)

        val reasons =
            listOf(
                ScoreInvalidation.Reason.BASELINE,
                ScoreInvalidation.Reason.WORKOUT,
                ScoreInvalidation.Reason.LATEST_VALUE,
                ScoreInvalidation.Reason.SOURCE_DELETION,
                ScoreInvalidation.Reason.SOURCE_REPLACEMENT,
                ScoreInvalidation.Reason.UNKNOWN,
            )

        reasons.forEach { reason ->
            val result =
                ScoreInvalidation.dependencyClosure(
                    changed = changed,
                    reason = reason,
                    retentionStart = retentionStart,
                    today = today,
                )
            assertEquals(
                "Reason $reason must repair the full retained suffix through today",
                ScoreInvalidation.AffectedRange(changedDay, today),
                result,
            )
        }
    }

    @Test
    fun `dependency closure for pre-retention correction returns the retained suffix`() {
        val retentionStart = LocalDate.of(2024, 1, 1)
        val today = LocalDate.of(2026, 9, 23)

        assertEquals(
            ScoreInvalidation.AffectedRange(retentionStart, today),
            ScoreInvalidation.dependencyClosure(
                ScoreInvalidation.AffectedRange(retentionStart.minusDays(10), retentionStart),
                ScoreInvalidation.Reason.WORKOUT,
                retentionStart,
                today,
            ),
        )

        assertEquals(
            ScoreInvalidation.AffectedRange(retentionStart, today),
            ScoreInvalidation.dependencyClosure(
                ScoreInvalidation.AffectedRange(retentionStart.minusDays(20), retentionStart.minusDays(5)),
                ScoreInvalidation.Reason.WORKOUT,
                retentionStart,
                today,
            ),
        )
    }

    @Test
    fun `dependency closure for future-only correction returns null`() {
        val retentionStart = LocalDate.of(2024, 1, 1)
        val today = LocalDate.of(2026, 9, 23)
        val futureChanged = ScoreInvalidation.AffectedRange(today.plusDays(1), today.plusDays(5))

        assertEquals(
            null,
            ScoreInvalidation.dependencyClosure(
                futureChanged,
                ScoreInvalidation.Reason.WORKOUT,
                retentionStart,
                today,
            ),
        )
        assertEquals(
            null,
            ScoreInvalidation.dependencyClosure(
                futureChanged,
                ScoreInvalidation.Reason.RECOMMENDATION_EXAMPLES,
                retentionStart,
                today,
            ),
        )

        val invertedChanged = ScoreInvalidation.AffectedRange(today, today.minusDays(1))
        assertEquals(
            null,
            ScoreInvalidation.dependencyClosure(
                invertedChanged,
                ScoreInvalidation.Reason.WORKOUT,
                retentionStart,
                today,
            ),
        )
    }

    @Test
    fun `dependency closure for recommendation-only case ends at day plus 30`() {
        val changedDay = LocalDate.of(2025, 1, 1)
        val retentionStart = LocalDate.of(2024, 1, 1)
        val today = LocalDate.of(2026, 9, 23)

        assertEquals(
            ScoreInvalidation.AffectedRange(changedDay, changedDay.plusDays(30)),
            ScoreInvalidation.dependencyClosure(
                ScoreInvalidation.AffectedRange(changedDay, changedDay),
                ScoreInvalidation.Reason.RECOMMENDATION_EXAMPLES,
                retentionStart,
                today,
            ),
        )

        val recentChanged = today.minusDays(10)
        assertEquals(
            ScoreInvalidation.AffectedRange(recentChanged, today),
            ScoreInvalidation.dependencyClosure(
                ScoreInvalidation.AffectedRange(recentChanged, recentChanged),
                ScoreInvalidation.Reason.RECOMMENDATION_EXAMPLES,
                retentionStart,
                today,
            ),
        )

        val oldChanged = LocalDate.of(2020, 1, 1)
        assertEquals(
            null,
            ScoreInvalidation.dependencyClosure(
                ScoreInvalidation.AffectedRange(oldChanged, oldChanged.plusDays(5)),
                ScoreInvalidation.Reason.RECOMMENDATION_EXAMPLES,
                retentionStart,
                today,
            ),
        )
    }

    @Test
    fun `reasonFromStored maps recognized strings and falls back to UNKNOWN`() {
        val reasons =
            listOf(
                "BASELINE" to ScoreInvalidation.Reason.BASELINE,
                "WORKOUT" to ScoreInvalidation.Reason.WORKOUT,
                "LATEST_VALUE" to ScoreInvalidation.Reason.LATEST_VALUE,
                "SOURCE_DELETION" to ScoreInvalidation.Reason.SOURCE_DELETION,
                "SOURCE_REPLACEMENT" to ScoreInvalidation.Reason.SOURCE_REPLACEMENT,
                "RECOMMENDATION_EXAMPLES" to ScoreInvalidation.Reason.RECOMMENDATION_EXAMPLES,
                "HOT_TIER_ROLLUP" to ScoreInvalidation.Reason.HOT_TIER_ROLLUP,
                "RETENTION_CLEANUP" to ScoreInvalidation.Reason.RETENTION_CLEANUP,
                "RECORD_DELETION" to ScoreInvalidation.Reason.RECORD_DELETION,
                "INTERVAL_CORRECTION" to ScoreInvalidation.Reason.INTERVAL_CORRECTION,
                "RESTORE_REGENERATE" to ScoreInvalidation.Reason.RESTORE_REGENERATE,
                "AUTHORITATIVE_SOURCE_REPLACEMENT" to ScoreInvalidation.Reason.AUTHORITATIVE_SOURCE_REPLACEMENT,
                "UNKNOWN" to ScoreInvalidation.Reason.UNKNOWN,
            )

        reasons.forEach { (stored, expected) ->
            assertEquals(
                "Stored string '$stored' must map to $expected",
                expected,
                ScoreInvalidation.reasonFromStored(stored),
            )
        }

        assertEquals(ScoreInvalidation.Reason.UNKNOWN, ScoreInvalidation.reasonFromStored("OLDER_APP_REASON"))
        assertEquals(ScoreInvalidation.Reason.UNKNOWN, ScoreInvalidation.reasonFromStored("unrecognized"))
        assertEquals(ScoreInvalidation.Reason.UNKNOWN, ScoreInvalidation.reasonFromStored(""))
    }

    @Test
    fun `every scoring lookback constant is within the max dependent window`() {
        val lookbacks =
            listOf(
                ScoringConstants.ACUTE_DAYS,
                ScoringConstants.CHRONIC_DAYS,
                ScoringConstants.BASELINE_DAYS,
                ScoringConstants.HRV_SIGMA_WINDOW_DAYS.toLong(),
                ScoringConstants.CIRCADIAN_CONSISTENCY_WINDOW_DAYS.toLong(),
                ScoringConstants.MATURE_DATA_TENURE_DAYS.toLong(),
                // the 84-day TRIMP fetch window (ScoringRepositoryImpl, DailyRecomputeSupport walk-forward)
                ScoringConstants.CHRONIC_DAYS * 2,
            )
        lookbacks.forEach { days ->
            assertTrue(
                "lookback $days exceeds MAX_DEPENDENT_WINDOW_DAYS=${ScoreInvalidation.MAX_DEPENDENT_WINDOW_DAYS} " +
                    "— raise the constant rather than weakening this test",
                days <= ScoreInvalidation.MAX_DEPENDENT_WINDOW_DAYS,
            )
        }
    }

    @Test
    fun `merge combines multiple affected ranges into bounding range`() {
        val r1 = ScoreInvalidation.AffectedRange(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 10))
        val r2 = ScoreInvalidation.AffectedRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 8))
        val r3 = ScoreInvalidation.AffectedRange(LocalDate.of(2026, 1, 7), LocalDate.of(2026, 1, 15))

        val merged = ScoreInvalidation.merge(r1, null, r2, r3)
        assertEquals(LocalDate.of(2026, 1, 1), merged?.start)
        assertEquals(LocalDate.of(2026, 1, 15), merged?.endInclusive)
    }

    @Test
    fun `merge returns null when all ranges are null or empty`() {
        val merged = ScoreInvalidation.merge(null, null)
        assertEquals(null, merged)
        assertEquals(null, ScoreInvalidation.merge(emptyList()))
    }

    @Test
    fun `example fan-out range covers 30 days after the correction when retention and today allow it`() {
        val correctionDate = LocalDate.of(2026, 1, 1)
        val today = LocalDate.of(2026, 6, 1)
        val retentionStart = LocalDate.of(2020, 1, 1)

        val result = ScoreInvalidation.exampleFanOutRange(correctionDate, today, retentionStart)

        assertEquals(correctionDate, result?.start)
        assertEquals(correctionDate.plusDays(30), result?.endInclusive)
    }

    @Test
    fun `example fan-out range never extends past today`() {
        val correctionDate = LocalDate.of(2026, 1, 1)
        val today = LocalDate.of(2026, 1, 10)
        val retentionStart = LocalDate.of(2020, 1, 1)

        val result = ScoreInvalidation.exampleFanOutRange(correctionDate, today, retentionStart)

        assertEquals(correctionDate, result?.start)
        assertEquals(today, result?.endInclusive)
    }

    @Test
    fun `example fan-out range never starts before retention`() {
        val correctionDate = LocalDate.of(2020, 1, 1)
        val today = LocalDate.of(2026, 1, 1)
        val retentionStart = LocalDate.of(2020, 1, 15)

        val result = ScoreInvalidation.exampleFanOutRange(correctionDate, today, retentionStart)

        assertEquals(retentionStart, result?.start)
        assertEquals(correctionDate.plusDays(30), result?.endInclusive)
    }

    @Test
    fun `example fan-out range is null when the correction predates retention by more than 30 days`() {
        val correctionDate = LocalDate.of(2020, 1, 1)
        val today = LocalDate.of(2026, 1, 1)
        val retentionStart = LocalDate.of(2020, 3, 1)

        val result = ScoreInvalidation.exampleFanOutRange(correctionDate, today, retentionStart)

        assertEquals(null, result)
    }

    @Test
    fun `example fan-out range is null for a correction dated after today`() {
        val correctionDate = LocalDate.of(2026, 6, 1)
        val today = LocalDate.of(2026, 1, 1)
        val retentionStart = LocalDate.of(2020, 1, 1)

        val result = ScoreInvalidation.exampleFanOutRange(correctionDate, today, retentionStart)

        assertEquals(null, result)
    }

    @Test
    fun `example selection lookback stays within the max dependent window`() {
        assertTrue(
            "EXAMPLE_SELECTION_LOOKBACK_DAYS=${ScoreInvalidation.EXAMPLE_SELECTION_LOOKBACK_DAYS} exceeds " +
                "MAX_DEPENDENT_WINDOW_DAYS=${ScoreInvalidation.MAX_DEPENDENT_WINDOW_DAYS}",
            ScoreInvalidation.EXAMPLE_SELECTION_LOOKBACK_DAYS <= ScoreInvalidation.MAX_DEPENDENT_WINDOW_DAYS,
        )
    }
}
