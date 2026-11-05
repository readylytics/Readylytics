package app.readylytics.health.core.database.testutil

import app.readylytics.health.core.database.data.repository.BodyMetricsDataLoader
import app.readylytics.health.core.database.data.repository.ScoringDayDataLoader
import app.readylytics.health.core.database.data.repository.ScoringHeartRateDataLoader
import app.readylytics.health.core.database.data.repository.ScoringSeriesLoader

/** The data loaders a test hands to [createScoringRepositoryForTest]. */
internal data class ScoringTestLoaders(
    val dataLoader: ScoringDayDataLoader,
    val bodyMetricsDataLoader: BodyMetricsDataLoader,
    val seriesLoader: ScoringSeriesLoader,
    val heartRateDataLoader: ScoringHeartRateDataLoader,
)
