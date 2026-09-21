package app.readylytics.health.benchmark

import app.readylytics.health.core.model.domain.model.DomainHeartRateRecord
import app.readylytics.health.core.model.domain.model.DomainHeartRateSample
import java.time.Instant

object HealthParentFixture {
    private const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000
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
}
