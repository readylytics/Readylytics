package app.readylytics.health.core.model.domain.scoring

import app.readylytics.health.core.model.domain.model.Result
import java.time.LocalDate

/** Serializes a settings recompute with health mutations and defers it during maintenance. */
interface RecomputeToday {
    suspend fun execute(day: LocalDate): Result<Unit>
}
