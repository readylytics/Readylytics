package app.readylytics.health.benchmark

import app.readylytics.health.core.model.domain.model.DomainHeartRateRecord

/**
 * The three scale points every Phase 0 measurement is taken at. Peak memory is expected to be flat
 * across these three; wall time is expected to be roughly linear. A measurement taken at only one
 * scale cannot distinguish those two, which is the whole point of having three.
 *
 * Both shapes produce exactly `totalSamples` samples inside [HealthParentFixture]'s fixed 30-day
 * window, so they are directly comparable:
 *  - [densePages] — few parents, many nested samples each (transform-buffer / rollup stress).
 *  - [sparsePages] — one sample per parent (per-parent source-lookup stress).
 */
object BaselineScalePoints {
    val SAMPLE_COUNTS: List<Int> = listOf(250_000, 500_000, 1_000_000)

    /** Matches Health Connect 1.1.0's documented default `ReadRecordsRequest` page size. */
    const val PAGE_SIZE: Int = 1_000

    private const val SAMPLES_PER_DENSE_PARENT = 1_000

    fun densePages(totalSamples: Int): Sequence<List<DomainHeartRateRecord>> {
        require(totalSamples % SAMPLES_PER_DENSE_PARENT == 0) {
            "totalSamples must be a multiple of $SAMPLES_PER_DENSE_PARENT"
        }
        return HealthParentFixture.pages(
            parentCount = totalSamples / SAMPLES_PER_DENSE_PARENT,
            samplesPerParent = SAMPLES_PER_DENSE_PARENT,
            pageSize = PAGE_SIZE,
        )
    }

    fun sparsePages(totalSamples: Int): Sequence<List<DomainHeartRateRecord>> =
        HealthParentFixture.pages(
            parentCount = totalSamples,
            samplesPerParent = 1,
            pageSize = PAGE_SIZE,
        )
}
