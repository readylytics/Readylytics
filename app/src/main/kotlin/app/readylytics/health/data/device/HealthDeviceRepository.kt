package app.readylytics.health.data.device

import android.os.SystemClock
import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.HrvDao
import app.readylytics.health.data.local.dao.SleepSessionDao
import app.readylytics.health.data.local.dao.WorkoutDao
import app.readylytics.health.domain.repository.HealthConnectRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages device discovery and caching with Flow-based approach.
 * Provides a clean abstraction over device discovery with proper TTL and thread-safety.
 *
 * Replaces manual Mutex-based caching in SettingsRepository with:
 * - Automatic TTL expiration (5 minutes)
 * - No race conditions (atomic updates via StateFlow)
 * - Clear invalidation API for post-sync refresh
 */
@Singleton
class HealthDeviceRepository
    @Inject
    constructor(
        private val sleepSessionDao: SleepSessionDao,
        private val heartRateDao: HeartRateDao,
        private val hrvDao: HrvDao,
        private val workoutDao: WorkoutDao,
        private val healthConnectRepository: HealthConnectRepository,
    ) {
        // TTL in milliseconds (5 minutes)
        companion object {
            private const val CACHE_TTL_MS = 5 * 60 * 1000L
        }

        private data class CacheEntry(
            val devices: List<String>,
            val timestampMs: Long,
        ) {
            fun isExpired(nowMs: Long): Boolean = nowMs - timestampMs > CACHE_TTL_MS
        }

        // Flow-based cache (better than manual Mutex + mutable var)
        private val deviceCache = MutableStateFlow<CacheEntry?>(null)

        // Mutex to prevent concurrent fetches
        private val fetchMutex = Mutex()

        /**
         * Get available devices with automatic caching.
         * Cache invalidates after TTL or when explicitly cleared.
         *
         * Thread-safe: Multiple concurrent calls won't cause double-fetch.
         * The Mutex ensures only the first caller fetches; others wait for cached result.
         */
        suspend fun getAvailableDevices(): List<String> {
            // Check if cached and not expired
            val now = SystemClock.elapsedRealtime()
            val cached = deviceCache.value
            if (cached != null && !cached.isExpired(now)) {
                return cached.devices
            }

            // Fetch with Mutex to prevent double-fetch
            return fetchMutex.withLock {
                // Double-check after acquiring lock in case another coroutine fetched
                val rechecked = deviceCache.value
                if (rechecked != null && !rechecked.isExpired(SystemClock.elapsedRealtime())) {
                    return@withLock rechecked.devices
                }
                fetchAndCacheDevices()
            }
        }

        /**
         * Invalidate cache immediately (call after sync completes).
         * This ensures next getAvailableDevices() call fetches fresh data.
         */
        fun invalidateCache() {
            deviceCache.value = null
        }

        /**
         * Fetch devices from both DB and HC API, then cache with timestamp.
         * DB devices: Extracted from all existing records (device names from all data types)
         * HC devices: Discovered via Health Connect API
         */
        private suspend fun fetchAndCacheDevices(): List<String> {
            // Parallel fetch from DB and HC API
            val allDevices =
                coroutineScope {
                    val dbDevicesAsync =
                        async {
                            sleepSessionDao.getDistinctDeviceNames() +
                                heartRateDao.getDistinctDeviceNames() +
                                hrvDao.getDistinctDeviceNames() +
                                workoutDao.getDistinctDeviceNames()
                        }

                    val hcDevicesAsync =
                        async {
                            healthConnectRepository.discoverDevices(windowDays = 14)
                        }

                    (dbDevicesAsync.await() + hcDevicesAsync.await())
                        .filterNot { it.isBlank() }
                        .distinct()
                        .sorted()
                }

            // Cache with timestamp for TTL tracking (using elapsedRealtime for monotonic clock)
            deviceCache.value = CacheEntry(allDevices, SystemClock.elapsedRealtime())

            return allDevices
        }
    }
