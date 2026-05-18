package com.gregor.lauritz.healthdashboard.domain.cache

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
                "Hit rate should be 90% (1 compute / 10 calls), got $hitRate%",
                computeCallCount == 1,
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

            assertEquals("Second call after TTL should trigger recompute", 2, computeCallCount)
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
            assertEquals("After invalidate, should recompute", 2, computeCallCount)
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

            assertEquals("Each unique date triggers its own compute", 2, computeCallCount)
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
}
