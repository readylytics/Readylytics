package com.gregor.lauritz.healthdashboard.domain.cache

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
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

    fun isExpired(nowMs: Long = SystemClock.elapsedRealtime()): Boolean = nowMs - timestampMs > TTL_MS
}

@Singleton
class DailyMetricCache(
    private val clockMs: () -> Long,
) {
    @Inject
    constructor() : this({ SystemClock.elapsedRealtime() })

    private val cache = MutableStateFlow<CachedDailyMetrics?>(null)
    private val mutex = Mutex()

    suspend fun getDailyMetrics(
        date: LocalDate,
        compute: suspend (LocalDate) -> Pair<Int, Int>,
    ): CachedDailyMetrics {
        val now = clockMs()
        val cached = cache.value
        if (cached?.date == date && !cached.isExpired(now)) {
            return cached
        }

        return mutex.withLock {
            val nowInner = clockMs()
            val rechecked = cache.value
            if (rechecked?.date == date && !rechecked.isExpired(nowInner)) {
                return@withLock rechecked
            }

            val (sleepScore, loadScore) = compute(date)
            CachedDailyMetrics(
                sleepScore = sleepScore,
                loadScore = loadScore,
                date = date,
                timestampMs = clockMs(),
            ).also { cache.value = it }
        }
    }

    fun invalidate() {
        cache.value = null
    }
}
