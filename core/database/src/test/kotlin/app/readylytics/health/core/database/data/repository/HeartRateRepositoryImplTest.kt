package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HrvDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.model.domain.repository.HeartRateResolution
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class HeartRateRepositoryImplTest {
    private val heartRateDao = mockk<HeartRateDao>(relaxed = true)
    private val hrvDao = mockk<HrvDao>(relaxed = true)
    private val minuteBucketDao = mockk<MinuteBucketDao>(relaxed = true)
    private lateinit var repository: HeartRateRepositoryImpl

    @Before
    fun setup() {
        repository =
            HeartRateRepositoryImpl(
                heartRateDao = heartRateDao,
                hrvDao = hrvDao,
                minuteBucketDao = minuteBucketDao,
            )
    }

    @Test
    fun `getRecoveryWindowSamples returns hot-tier samples with RAW resolution when present`() =
        runTest {
            coEvery { heartRateDao.getByTimeRange(1_000L, 5_000L) } returns
                listOf(
                    heartRateEntityFixture(timestampMs = 2_000L, beatsPerMinute = 70),
                )
            coEvery { minuteBucketDao.getBucketsInTimeRange(1_000L, 5_000L) } returns emptyList()

            val series = repository.getRecoveryWindowSamples(1_000L, 5_000L)

            assertEquals(HeartRateResolution.RAW, series.resolution)
            assertEquals(1, series.points.size)
        }

    @Test
    fun `getRecoveryWindowSamples falls back to reconstructed warm-tier samples when hot tier is empty`() =
        runTest {
            coEvery { heartRateDao.getByTimeRange(1_000L, 5_000L) } returns emptyList()
            coEvery { minuteBucketDao.getBucketsInTimeRange(1_000L, 5_000L) } returns
                listOf(
                    minuteBucketFixture(
                        bucketStartMs = 1_000L,
                        bucketEndMs = 60_999L,
                        avgBpm = 65.0,
                        minBpm = 60,
                        maxBpm = 70,
                        sampleCount = 3,
                    ),
                )

            val series = repository.getRecoveryWindowSamples(1_000L, 5_000L)

            assertEquals(HeartRateResolution.RECONSTRUCTED, series.resolution)
            assertEquals(3, series.points.size)
        }

    @Test
    fun `getRecoveryWindowSamples merges both tiers and sorts by timestamp when both are non-empty`() =
        runTest {
            coEvery { heartRateDao.getByTimeRange(1_000L, 5_000L) } returns
                listOf(
                    heartRateEntityFixture(timestampMs = 4_000L, beatsPerMinute = 80),
                )
            coEvery { minuteBucketDao.getBucketsInTimeRange(1_000L, 5_000L) } returns
                listOf(
                    minuteBucketFixture(
                        bucketStartMs = 1_000L,
                        bucketEndMs = 60_999L,
                        avgBpm = 65.0,
                        minBpm = 60,
                        maxBpm = 70,
                        sampleCount = 3,
                    ),
                )

            val series = repository.getRecoveryWindowSamples(1_000L, 5_000L)

            assertEquals(HeartRateResolution.RECONSTRUCTED, series.resolution)
            assertEquals(4, series.points.size)
            assertEquals(series.points.map { it.timestampMs }, series.points.map { it.timestampMs }.sorted())
        }

    @Test
    fun `observeTimelineWithResolution merges both tiers and sorts by timestamp when both are non-empty`() =
        runTest {
            // Regression for R2-UI-002 review finding: DataRollupManager's hot-to-warm rollup cutoff
            // (RetentionBounds.resolveHotTierCutoffMs) is a continuous instant, not day-aligned, so a
            // single calendar day routinely straddles both tiers. Hot being non-empty must not short-
            // circuit the warm-tier read -- both tiers have to be merged whenever warm is non-empty.
            every { heartRateDao.observeByTimeRange(1_000L, 5_000L) } returns
                flowOf(
                    listOf(
                        heartRateEntityFixture(timestampMs = 4_000L, beatsPerMinute = 80),
                    ),
                )
            coEvery { minuteBucketDao.getBucketsInTimeRange(1_000L, 5_000L) } returns
                listOf(
                    minuteBucketFixture(
                        bucketStartMs = 1_000L,
                        bucketEndMs = 60_999L,
                        avgBpm = 65.0,
                        minBpm = 60,
                        maxBpm = 70,
                        sampleCount = 3,
                    ),
                )

            val series = repository.observeTimelineWithResolution(1_000L, 5_000L).first()

            assertEquals(HeartRateResolution.RECONSTRUCTED, series.resolution)
            assertEquals(4, series.points.size)
            assertEquals(series.points.map { it.timestampMs }, series.points.map { it.timestampMs }.sorted())
        }

    private fun heartRateEntityFixture(
        timestampMs: Long,
        beatsPerMinute: Int,
        sourceRecordRef: Long = 1L,
        recordType: String = "RESTING",
    ): HeartRateRecordEntity =
        HeartRateRecordEntity(
            sourceRecordRef = sourceRecordRef,
            timestampMs = timestampMs,
            beatsPerMinute = beatsPerMinute,
            recordType = recordType,
        )

    private fun minuteBucketFixture(
        bucketStartMs: Long,
        bucketEndMs: Long,
        avgBpm: Double,
        minBpm: Int,
        maxBpm: Int,
        sampleCount: Int,
        recordType: String = "RESTING",
        sessionId: String = "",
    ): HrMinuteBucketEntity =
        HrMinuteBucketEntity(
            bucketStartMs = bucketStartMs,
            bucketEndMs = bucketEndMs,
            minBpm = minBpm,
            maxBpm = maxBpm,
            avgBpm = avgBpm,
            sampleCount = sampleCount,
            recordType = recordType,
            sessionId = sessionId,
        )
}
