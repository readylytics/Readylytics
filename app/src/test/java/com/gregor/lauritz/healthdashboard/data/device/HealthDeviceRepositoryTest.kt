package com.gregor.lauritz.healthdashboard.data.device

import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class HealthDeviceRepositoryTest {
    private lateinit var sleepSessionDao: SleepSessionDao
    private lateinit var heartRateDao: HeartRateDao
    private lateinit var hrvDao: HrvDao
    private lateinit var workoutDao: WorkoutDao
    private lateinit var healthConnectRepository: HealthConnectRepository
    private lateinit var repository: HealthDeviceRepository

    @Before
    fun setup() {
        sleepSessionDao = mockk()
        heartRateDao = mockk()
        hrvDao = mockk()
        workoutDao = mockk()
        healthConnectRepository = mockk()

        repository =
            HealthDeviceRepository(
                sleepSessionDao,
                heartRateDao,
                hrvDao,
                workoutDao,
                healthConnectRepository,
            )
    }

    @Test
    fun `getAvailableDevices fetches and caches devices`() = runTest {
        val dbDevices = listOf("Device1", "Device2")
        val hcDevices = listOf("Device3")
        val expected = listOf("Device1", "Device2", "Device3").sorted()

        coEvery { sleepSessionDao.getDistinctDeviceNames() } returns listOf("Device1")
        coEvery { heartRateDao.getDistinctDeviceNames() } returns listOf("Device2")
        coEvery { hrvDao.getDistinctDeviceNames() } returns emptyList()
        coEvery { workoutDao.getDistinctDeviceNames() } returns emptyList()
        coEvery { healthConnectRepository.discoverDevices(any()) } returns hcDevices

        // First call fetches and caches
        val result1 = repository.getAvailableDevices()
        assertEquals(expected, result1)

        // Verify all DAOs were called
        coVerify(exactly = 1) { sleepSessionDao.getDistinctDeviceNames() }
        coVerify(exactly = 1) { heartRateDao.getDistinctDeviceNames() }
        coVerify(exactly = 1) { hrvDao.getDistinctDeviceNames() }
        coVerify(exactly = 1) { workoutDao.getDistinctDeviceNames() }
        coVerify(exactly = 1) { healthConnectRepository.discoverDevices(any()) }
    }

    @Test
    fun `getAvailableDevices returns cached value without additional queries`() = runTest {
        coEvery { sleepSessionDao.getDistinctDeviceNames() } returns listOf("Device1")
        coEvery { heartRateDao.getDistinctDeviceNames() } returns listOf("Device2")
        coEvery { hrvDao.getDistinctDeviceNames() } returns emptyList()
        coEvery { workoutDao.getDistinctDeviceNames() } returns emptyList()
        coEvery { healthConnectRepository.discoverDevices(any()) } returns listOf("Device3")

        // First call
        val result1 = repository.getAvailableDevices()
        assertEquals(3, result1.size)

        // Clear all mock call counts
        io.mockk.clearAllMocks(answers = false)

        // Reset mocks to throw if called (verify they're NOT called)
        coEvery { sleepSessionDao.getDistinctDeviceNames() } throws AssertionError("Should not be called")
        coEvery { heartRateDao.getDistinctDeviceNames() } throws AssertionError("Should not be called")
        coEvery { hrvDao.getDistinctDeviceNames() } throws AssertionError("Should not be called")
        coEvery { workoutDao.getDistinctDeviceNames() } throws AssertionError("Should not be called")
        coEvery {
            healthConnectRepository.discoverDevices(any())
        } throws AssertionError("Should not be called")

        // Second call should use cache
        val result2 = repository.getAvailableDevices()
        assertEquals(result1, result2)
    }

    @Test
    fun `invalidateCache clears cached devices`() = runTest {
        coEvery { sleepSessionDao.getDistinctDeviceNames() } returns listOf("Device1")
        coEvery { heartRateDao.getDistinctDeviceNames() } returns emptyList()
        coEvery { hrvDao.getDistinctDeviceNames() } returns emptyList()
        coEvery { workoutDao.getDistinctDeviceNames() } returns emptyList()
        coEvery { healthConnectRepository.discoverDevices(any()) } returns listOf("Device2")

        // Fetch and cache
        val result1 = repository.getAvailableDevices()
        assertEquals(2, result1.size)

        // Invalidate cache
        repository.invalidateCache()

        // Reset mocks with different data
        coEvery { sleepSessionDao.getDistinctDeviceNames() } returns listOf("Device3")
        coEvery { heartRateDao.getDistinctDeviceNames() } returns emptyList()
        coEvery { hrvDao.getDistinctDeviceNames() } returns emptyList()
        coEvery { workoutDao.getDistinctDeviceNames() } returns emptyList()
        coEvery { healthConnectRepository.discoverDevices(any()) } returns listOf("Device4")

        // Next call should fetch fresh data
        val result2 = repository.getAvailableDevices()
        assertEquals(listOf("Device3", "Device4").sorted(), result2)
    }

    @Test
    fun `getAvailableDevices filters blank device names`() = runTest {
        coEvery { sleepSessionDao.getDistinctDeviceNames() } returns listOf("Device1", "")
        coEvery { heartRateDao.getDistinctDeviceNames() } returns listOf("  ", "Device2")
        coEvery { hrvDao.getDistinctDeviceNames() } returns emptyList()
        coEvery { workoutDao.getDistinctDeviceNames() } returns emptyList()
        coEvery { healthConnectRepository.discoverDevices(any()) } returns emptyList()

        val result = repository.getAvailableDevices()

        // Blank strings should be filtered out
        assertEquals(listOf("Device1", "Device2"), result)
    }

    @Test
    fun `getAvailableDevices removes duplicates`() = runTest {
        coEvery { sleepSessionDao.getDistinctDeviceNames() } returns listOf("Device1", "Device2")
        coEvery { heartRateDao.getDistinctDeviceNames() } returns listOf("Device2", "Device3")
        coEvery { hrvDao.getDistinctDeviceNames() } returns listOf("Device1")
        coEvery { workoutDao.getDistinctDeviceNames() } returns emptyList()
        coEvery { healthConnectRepository.discoverDevices(any()) } returns listOf("Device3")

        val result = repository.getAvailableDevices()

        // All duplicates should be removed and sorted
        assertEquals(listOf("Device1", "Device2", "Device3"), result)
    }

    @Test
    fun `getAvailableDevices sorts results`() = runTest {
        coEvery { sleepSessionDao.getDistinctDeviceNames() } returns listOf("Zebra")
        coEvery { heartRateDao.getDistinctDeviceNames() } returns listOf("Apple")
        coEvery { hrvDao.getDistinctDeviceNames() } returns emptyList()
        coEvery { workoutDao.getDistinctDeviceNames() } returns listOf("Mango")
        coEvery { healthConnectRepository.discoverDevices(any()) } returns emptyList()

        val result = repository.getAvailableDevices()

        // Results should be alphabetically sorted
        assertEquals(listOf("Apple", "Mango", "Zebra"), result)
    }

    @Test
    fun `invalidateCache clears cached devices and forces fresh fetch`() = runTest {
        coEvery { sleepSessionDao.getDistinctDeviceNames() } returns listOf("Device1")
        coEvery { heartRateDao.getDistinctDeviceNames() } returns emptyList()
        coEvery { hrvDao.getDistinctDeviceNames() } returns emptyList()
        coEvery { workoutDao.getDistinctDeviceNames() } returns emptyList()
        coEvery { healthConnectRepository.discoverDevices(any()) } returns listOf("Device2")

        // Fetch and cache
        repository.getAvailableDevices()

        // Invalidate cache
        repository.invalidateCache()

        // Reset mocks with different data
        coEvery { sleepSessionDao.getDistinctDeviceNames() } returns listOf("Device3")
        coEvery { heartRateDao.getDistinctDeviceNames() } returns emptyList()
        coEvery { hrvDao.getDistinctDeviceNames() } returns emptyList()
        coEvery { workoutDao.getDistinctDeviceNames() } returns emptyList()
        coEvery { healthConnectRepository.discoverDevices(any()) } returns listOf("Device4")

        // Next call should fetch fresh data
        val result = repository.getAvailableDevices()
        assertEquals(listOf("Device3", "Device4").sorted(), result)
    }
}
