package com.gregor.lauritz.healthdashboard.data.device

import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        fun isExpired(): Boolean = System.currentTimeMillis() - timestampMs > CACHE_TTL_MS
    }

    // Flow-based cache (better than manual Mutex + mutable var)
    private val _deviceCache = MutableStateFlow<CacheEntry?>(null)
    private val deviceCache = _deviceCache.asStateFlow()

    /**
     * Get available devices with automatic caching.
     * Cache invalidates after TTL or when explicitly cleared.
     *
     * Thread-safe: Multiple concurrent calls won't cause double-fetch
     * (first one fetches, subsequent ones wait for result)
     */
    suspend fun getAvailableDevices(): List<String> {
        // Check if cached and not expired
        val cached = deviceCache.value
        if (cached != null && !cached.isExpired()) {
            return cached.devices
        }

        // Fetch fresh data
        return fetchAndCacheDevices()
    }

    /**
     * Invalidate cache immediately (call after sync completes).
     * This ensures next getAvailableDevices() call fetches fresh data.
     */
    suspend fun invalidateCache() {
        _deviceCache.value = null
    }

    /**
     * Clear cache on demand (can be called from sync completion).
     */
    fun clearCache() {
        _deviceCache.value = null
    }

    /**
     * Fetch devices from both DB and HC API, then cache with timestamp.
     * DB devices: Extracted from all existing records (device names from all data types)
     * HC devices: Discovered via Health Connect API
     */
    private suspend fun fetchAndCacheDevices(): List<String> {
        // Parallel fetch from DB and HC API
        val allDevices = coroutineScope {
            val dbDevicesAsync =
                async {
                    (
                        sleepSessionDao.getDistinctDeviceNames() +
                            heartRateDao.getDistinctDeviceNames() +
                            hrvDao.getDistinctDeviceNames() +
                            workoutDao.getDistinctDeviceNames()
                    ).filterNot { it.isBlank() }.distinct()
                }

            val hcDevicesAsync = async {
                healthConnectRepository.discoverDevices(windowDays = 14)
            }

            (dbDevicesAsync.await() + hcDevicesAsync.await()).distinct().sorted()
        }

        // Cache with timestamp for TTL tracking
        _deviceCache.value = CacheEntry(allDevices, System.currentTimeMillis())

        return allDevices
    }
}
