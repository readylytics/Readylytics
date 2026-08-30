package app.readylytics.health.core.database.data.repository

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Room-backed loaders `ScoringRepositoryImpl` reads a day's inputs through.
 *
 * Grouped into a parameter object for the same reason as [ScoringDayUseCases]: the repository's
 * constructor was already over the detekt `LongParameterList` threshold on a baseline entry, and
 * that entry is removed rather than rewritten (baseline edits need explicit approval).
 */
@Singleton
data class ScoringDataLoaders
    @Inject
    constructor(
        val day: ScoringDayDataLoader,
        val bodyMetrics: BodyMetricsDataLoader,
        val series: ScoringSeriesLoader,
    )
