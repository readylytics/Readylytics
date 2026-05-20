package com.gregor.lauritz.healthdashboard.domain.cache

import android.os.SystemClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class CachedDailyMetrics(
    val sleepScore: Int,
    val loadScore: Int,
    val date: LocalDate,
    val timestampMs: Long,
) {
    companion object {
        const val TTL_MS = 60 * 60 * 1000L // 1h
    }

    fun isExpired(nowMs: Long): Boolean = nowMs - timestampMs > TTL_MS
}

@Singleton
class DailyMetricCache(
    private val clockMs: () -> Long,
) {
    @Inject
    constructor() : this({ SystemClock.elapsedRealtime() })

    private val cache = mutableMapOf<LocalDate, CachedDailyMetrics>()
    private val mutex = Mutex()
    private val maxCacheSize = 30

    suspend fun getDailyMetrics(
        date: LocalDate,
        compute: suspend (LocalDate) -> Pair<Int, Int>,
    ): CachedDailyMetrics {
        val now = clockMs()
        val cachedFast = synchronized(cache) { cache[date] }
        if (cachedFast != null && !cachedFast.isExpired(now)) {
            return cachedFast
        }

        return mutex.withLock {
            val cachedInner = synchronized(cache) { cache[date] }
            if (cachedInner != null && !cachedInner.isExpired(clockMs())) {
                return@withLock cachedInner
            }

            val (sleepScore, loadScore) = compute(date)
            val newMetrics =
                CachedDailyMetrics(
                    sleepScore = sleepScore,
                    loadScore = loadScore,
                    date = date,
                    timestampMs = clockMs(),
                )

            synchronized(cache) {
                if (cache.size >= maxCacheSize) {
                    val oldestKey = cache.minByOrNull { it.value.timestampMs }?.key
                    if (oldestKey != null) {
                        cache.remove(oldestKey)
                    }
                }
                cache[date] = newMetrics
            }
            newMetrics
        }
    }

    fun invalidate() {
        synchronized(cache) {
            cache.clear()
        }
    }
}
