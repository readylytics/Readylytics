package app.readylytics.health.core.healthconnect.domain.sync

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

    /**
     * Detects transient Health Connect API failures.
     *
     * The rate-limit detection relies on error message strings ("rate limit", "too many requests",
     * "quota") which are NOT part of the stable Health Connect API contract. If Google changes
     * error message text in a future SDK release, rate-limit retries may stop working.
     *
     * This is currently acceptable because:
     * 1. IOException catch provides a fallback for most transient failures
     * 2. HC SDK changes are infrequent
     *
     * TODO: Monitor Health Connect SDK releases for typed rate-limit exceptions. If Google
     * introduces a dedicated exception class (e.g., RateLimitException), migrate to it.
     *
     * Note: TODO suppression is intentional — this documents a known fragility that requires
     * future monitoring of the HC SDK, and cannot be fixed until Google provides typed
     * rate-limit exceptions.
     */
    @Suppress("ForbiddenComment")
    private fun Throwable.isTransientHealthConnectFailure(): Boolean =
        this is IOException ||
            message?.contains("rate limit", ignoreCase = true) == true ||
            message?.contains("too many requests", ignoreCase = true) == true ||
            message?.contains("quota", ignoreCase = true) == true
}
