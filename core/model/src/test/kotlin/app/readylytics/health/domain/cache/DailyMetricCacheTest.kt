package app.readylytics.health.domain.cache

import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DailyMetricCacheTest {
    @Test
    fun `cache hit rate is 90 percent - 1 compute out of 10 calls`() =
        runTest {
            val fixedTime = 1000L
            val cache = DailyMetricCache(clockMs = { fixedTime })
            var computeCallCount = 0
            val today = LocalDate.now()

            repeat(10) {
                cache.getDailyMetrics(today) { _ ->
                    computeCallCount++
                    100 to 50
                }
            }

            val hitRate = (1.0 - computeCallCount.toDouble() / 10.0) * 100
            assertTrue(
                actual = computeCallCount == 1,
                message = "Hit rate should be 90% (1 compute / 10 calls), got $hitRate%",
            )
        }

    @Test
    fun `cache returns stale result after TTL expiry`() =
        runTest {
            var fakeTimeMs = 0L
            val cache = DailyMetricCache(clockMs = { fakeTimeMs })
            var computeCallCount = 0
            val today = LocalDate.now()

            cache.getDailyMetrics(today) { _ ->
                computeCallCount++
                100 to 50
            }
            assertEquals(1, computeCallCount)

            // Advance past 1h TTL
            fakeTimeMs = CachedDailyMetrics.TTL_MS + 1

            cache.getDailyMetrics(today) { _ ->
                computeCallCount++
                100 to 50
            }

            assertEquals(2, computeCallCount, "Second call after TTL should trigger recompute")
        }

    @Test
    fun `cache invalidate forces recompute on next call`() =
        runTest {
            val fixedTime = 1000L
            val cache = DailyMetricCache(clockMs = { fixedTime })
            var computeCallCount = 0
            val today = LocalDate.now()

            cache.getDailyMetrics(today) { _ ->
                computeCallCount++
                100 to 50
            }
            assertEquals(1, computeCallCount)

            cache.invalidate()

            cache.getDailyMetrics(today) { _ ->
                computeCallCount++
                80 to 40
            }
            assertEquals(2, computeCallCount, "After invalidate, should recompute")
        }

    @Test
    fun `different dates do not share cache entries`() =
        runTest {
            val fixedTime = 1000L
            val cache = DailyMetricCache(clockMs = { fixedTime })
            var computeCallCount = 0
            val today = LocalDate.now()
            val yesterday = today.minusDays(1)

            cache.getDailyMetrics(today) { _ ->
                computeCallCount++
                100 to 50
            }
            cache.getDailyMetrics(yesterday) { _ ->
                computeCallCount++
                90 to 45
            }

            assertEquals(2, computeCallCount, "Each unique date triggers its own compute")
        }

    @Test
    fun `cached result returns correct scores`() =
        runTest {
            val fixedTime = 1000L
            val cache = DailyMetricCache(clockMs = { fixedTime })
            val today = LocalDate.now()

            val result = cache.getDailyMetrics(today) { _ -> 77 to 42 }

            assertEquals(77, result.sleepScore)
            assertEquals(42, result.loadScore)
            assertEquals(today, result.date)
        }

    @Test
    fun `cache supports multi-entry up to 30 days`() =
        runTest {
            val fixedTime = 1000L
            val cache = DailyMetricCache(clockMs = { fixedTime })
            var computeCount = 0
            val baseDate = LocalDate.now()

            // Populate cache for 30 different days
            for (i in 0 until 30) {
                cache.getDailyMetrics(baseDate.minusDays(i.toLong())) { _ ->
                    computeCount++
                    100 to 50
                }
            }
            assertEquals(30, computeCount, "Should compute for 30 unique dates")

            // Re-read all 30 days, they should all hit the cache
            for (i in 0 until 30) {
                cache.getDailyMetrics(baseDate.minusDays(i.toLong())) { _ ->
                    computeCount++
                    100 to 50
                }
            }
            assertEquals(30, computeCount, "Should not recompute any of the 30 dates since capacity is 30")

            // Add a 31st day to trigger eviction
            cache.getDailyMetrics(baseDate.minusDays(30)) { _ ->
                computeCount++
                100 to 50
            }
            assertEquals(31, computeCount, "Should compute for the 31st unique date")
        }
}
