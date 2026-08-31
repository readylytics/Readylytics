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
    fun `affected range extends the full 84 days when today is far enough away`() {
        val changed = ScoreInvalidation.AffectedRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 10))
        val today = LocalDate.of(2026, 12, 1)
        val result = ScoreInvalidation.affectedRange(changed, today)
        assertEquals(LocalDate.of(2026, 1, 10).plusDays(84), result.endInclusive)
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
}
