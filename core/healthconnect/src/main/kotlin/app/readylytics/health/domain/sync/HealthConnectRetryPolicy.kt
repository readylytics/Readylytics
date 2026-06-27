package app.readylytics.health.domain.sync

import java.io.IOException
import kotlin.math.min
import kotlin.random.Random

internal class HealthConnectRetryPolicy(
    private val maxAttempts: Int = 5,
    private val initialDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 60_000,
    private val jitterRatio: Double = 0.20,
    private val random: Random = Random.Default,
) {
    fun shouldRetry(
        throwable: Throwable,
        attempt: Int,
    ): Boolean = attempt < maxAttempts && throwable.isTransientHealthConnectFailure()

    fun delayForAttempt(attempt: Int): Long {
        val exponential = initialDelayMs * (1L shl (attempt - 1).coerceAtLeast(0))
        val capped = min(exponential, maxDelayMs)
        val jitter = (capped * jitterRatio).toLong()
        return if (jitter == 0L) capped else capped + random.nextLong(-jitter, jitter + 1)
    }

    private fun Throwable.isTransientHealthConnectFailure(): Boolean =
        this is IOException ||
            message?.contains("rate limit", ignoreCase = true) == true ||
            message?.contains("too many requests", ignoreCase = true) == true ||
            message?.contains("quota", ignoreCase = true) == true
}
