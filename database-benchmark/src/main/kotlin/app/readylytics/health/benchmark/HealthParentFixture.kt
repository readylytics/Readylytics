package app.readylytics.health.benchmark

import app.readylytics.health.core.model.domain.model.DomainHeartRateRecord
import app.readylytics.health.core.model.domain.model.DomainHeartRateSample
import java.time.Instant

/**
 * Deterministic, seeded synthetic Health Connect page generator. Every shape below is derived
 * purely from `parent`/`sample` indices (no randomness, no health values) so a fixture built with
 * the same arguments always produces byte-identical records -- the "seed" recorded alongside a
 * benchmark run in `benchmark/BASELINE.md` is this generator's deterministic index scheme itself.
 *
 * Task 11 / §11 requires three distribution shapes, all of which this object can produce:
 *  1. **>1m one-sample parents** -- [pages] with `samplesPerParent = 1` and a large `parentCount`
 *     (sparse-parent shape: `HC-001`/`PERF-001`'s per-parent source-lookup stress case).
 *  2. **>1m nested samples, few dense parents** -- [pages] with a small `parentCount` and a large
 *     `samplesPerParent` (dense-parent shape: rollup/transform-buffer stress case).
 *  3. **Page-boundary extremes** -- [pageBoundaryExtremes], whose last page's last parent holds
 *     samples straddling both a minute boundary and this fixture's 30-day chunk boundary.
 */
object HealthParentFixture {
    private const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000
    private const val MINUTE_MS = 60_000L
    private val start = Instant.parse("2026-01-01T00:00:00Z")

    fun pages(
        parentCount: Int,
        samplesPerParent: Int,
        pageSize: Int,
    ): Sequence<List<DomainHeartRateRecord>> {
        require(parentCount > 0 && samplesPerParent > 0 && pageSize > 0)
        val slots = parentCount.toLong() * samplesPerParent
        require(slots <= WINDOW_MS)
        return (0 until parentCount)
            .asSequence()
            .map { parent ->
                DomainHeartRateRecord(
                    id = "fixture_source_$parent",
                    deviceName = "fixture-origin-${parent % 3}",
                    samples =
                        List(samplesPerParent) { sample ->
                            val offset = (parent.toLong() * samplesPerParent + sample) * WINDOW_MS / slots
                            DomainHeartRateSample(start.plusMillis(offset), 50 + (parent + sample) % 100)
                        },
                )
            }.chunked(pageSize)
    }

    /**
     * Page-boundary extremes (Task 11 / §11): [fillerParentCount] one-sample parents spread evenly
     * across the first `WINDOW_MS - 1 minute` of this fixture's 30-day window, followed by one final
     * "boundary parent" whose two samples sit at `WINDOW_MS - 1ms` and `WINDOW_MS` -- one millisecond
     * on either side of this fixture's chunk boundary. `WINDOW_MS` (30 days) is an exact multiple of
     * one minute, so that same instant is simultaneously a minute boundary, which is what lets one
     * fixture exercise both [app.readylytics.health.core.database.data.local.MinuteRollupStreamer]'s
     * carry-forward-until-proven-closed rule and a chunked resync's chunk-boundary handling at once.
     * The boundary parent always lands as the last parent of the last page, deterministically, for
     * any `pageSize` that evenly divides `fillerParentCount + 1` into whole pages plus a remainder.
     */
    fun pageBoundaryExtremes(
        fillerParentCount: Int,
        pageSize: Int,
    ): Sequence<List<DomainHeartRateRecord>> {
        require(fillerParentCount >= 0 && pageSize > 0)
        val fillerSpanMs = WINDOW_MS - MINUTE_MS
        val filler =
            (0 until fillerParentCount).map { parent ->
                val offset =
                    if (fillerParentCount <=
                        1
                    ) {
                        0L
                    } else {
                        parent.toLong() * fillerSpanMs / (fillerParentCount - 1)
                    }
                DomainHeartRateRecord(
                    id = "fixture_boundary_filler_$parent",
                    deviceName = "fixture-origin-${parent % 3}",
                    samples = listOf(DomainHeartRateSample(start.plusMillis(offset), 50 + parent % 100)),
                )
            }
        val boundaryParent =
            DomainHeartRateRecord(
                id = "fixture_boundary_parent",
                deviceName = "fixture-origin-0",
                samples =
                    listOf(
                        DomainHeartRateSample(start.plusMillis(WINDOW_MS - 1), 61),
                        DomainHeartRateSample(start.plusMillis(WINDOW_MS), 62),
                    ),
            )
        return (filler + boundaryParent).asSequence().chunked(pageSize)
    }

    /** The chunk-boundary instant [pageBoundaryExtremes]' boundary parent straddles. */
    fun chunkBoundary(): Instant = start.plusMillis(WINDOW_MS)
}
