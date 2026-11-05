package app.readylytics.health.core.healthconnect.data.healthconnect

import app.readylytics.health.core.model.di.IoDispatcher
import app.readylytics.health.core.model.domain.repository.HealthConnectPermissionRevokedException
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.repository.getOrNull
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.model.domain.util.logW
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scans every Health Connect source type in a trailing window and returns the distinct device names
 * found, so the source picker can offer them.
 */
@Singleton
class HealthConnectDeviceDiscovery
    @Inject
    constructor(
        private val clock: Clock,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        private val vitals: HealthConnectVitalsReader,
        private val bodyMetrics: HealthConnectBodyMetricsReader,
        private val exercise: HealthConnectExerciseReader,
        private val stepRecordReader: StepRecordReader,
    ) {
        suspend fun discoverDevices(windowDays: Int): List<String> =
            withContext(ioDispatcher) {
                try {
                    logD(
                        "HealthConnectRepository",
                    ) { "Discovering devices in $windowDays day window..." }
                    val from = clock.instant().minusSeconds(windowDays.toLong() * TimeUnit.DAYS.toSeconds(1))
                    val to = clock.instant()

                    val devices = scanAllSources(from, to)

                    logD(
                        "HealthConnectRepository",
                    ) { "Device discovery found ${devices.size} unique devices" }
                    devices.sorted()
                } catch (e: SecurityException) {
                    throw HealthConnectPermissionRevokedException(
                        cause = e,
                        operation = "discoverDevices",
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logE(
                        "HealthConnectRepository",
                        e,
                    ) { "Device discovery failed" }
                    emptyList()
                }
            }

        private suspend fun scanAllSources(
            from: Instant,
            to: Instant,
        ): Set<String> =
            coroutineScope {
                // Each read is wrapped so a single revoked/missing permission can't
                // cancel the whole scope and collapse discovery to an empty list.
                val sleepSessionsDeferred =
                    async { readOrEmpty { vitals.readSleepSessions(from, to, null) } }
                val hrDevicesDeferred =
                    async {
                        collectDeviceNames("Heart rate") { onNames ->
                            vitals.readHeartRateSamplesPaged(from, to, null, null) { page, _ ->
                                onNames(page.map { it.deviceName })
                            }
                        }
                    }
                val hrvDevicesDeferred =
                    async {
                        collectDeviceNames("HRV") { onNames ->
                            vitals.readHrvSamplesPaged(from, to, null, null) { page, _ ->
                                onNames(page.map { it.deviceName })
                            }
                        }
                    }
                val workoutRecordsDeferred =
                    // Discovery only reads deviceName -- reading routes here would add one
                    // IPC round-trip per workout and block the source picker for nothing.
                    async {
                        readOrEmpty {
                            exercise.readExerciseSessions(from, to, includeDetails = false, retryScope = null)
                        }
                    }
                val stepsRecordsDeferred =
                    async { readOrEmpty { stepRecordReader.readStepsRecords(from, to) } }
                val weightRecordsDeferred =
                    async { readOrEmpty { bodyMetrics.readWeightRecords(from, to, null) } }
                val bodyFatRecordsDeferred =
                    async { readOrEmpty { bodyMetrics.readBodyFatRecords(from, to, null) } }
                val bloodPressureRecordsDeferred =
                    async { readOrEmpty { bodyMetrics.readBloodPressureRecords(from, to, null) } }
                val spo2RecordsDeferred =
                    async { readOrEmpty { bodyMetrics.readOxygenSaturationRecords(from, to, null) } }
                val bodyTemperatureRecordsDeferred =
                    async { readOrEmpty { bodyMetrics.readBodyTemperatureRecords(from, to, null) } }

                val devices = mutableSetOf<String>()
                sleepSessionsDeferred.await().forEach { devices.add(it.deviceName) }
                devices.addAll(hrDevicesDeferred.await())
                devices.addAll(hrvDevicesDeferred.await())
                workoutRecordsDeferred.await().forEach { devices.add(it.deviceName) }

                // Steps are frequently the only data the phone records, so scanning
                // them here is what surfaces the phone as a selectable source device.
                stepsRecordsDeferred.await().forEach { devices.add(it.deviceName) }
                weightRecordsDeferred.await().forEach { devices.add(it.deviceName) }
                bodyFatRecordsDeferred.await().forEach { devices.add(it.deviceName) }
                bloodPressureRecordsDeferred.await().forEach { devices.add(it.deviceName) }
                spo2RecordsDeferred.await().forEach { devices.add(it.deviceName) }
                bodyTemperatureRecordsDeferred.await().forEach { devices.add(it.deviceName) }
                devices
            }

        private suspend fun <T> readOrEmpty(block: suspend () -> ReadOutcome<List<T>>): List<T> =
            try {
                block().getOrNull() ?: emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logW("HealthConnectRepository", e) {
                    "Read failed; returning empty list"
                }
                emptyList()
            }

        /**
         * HC-001: collects device names from a streaming paged read without ever materializing the
         * full from..to record list. [readPaged] is expected to be a partial application of
         * readHeartRateSamplesPaged/readHrvSamplesPaged that forwards each page's device names to
         * its callback.
         */
        private suspend fun collectDeviceNames(
            label: String,
            readPaged: suspend (onNames: suspend (List<String>) -> Unit) -> Unit,
        ): Set<String> {
            val localDevices = mutableSetOf<String>()
            try {
                readPaged { names -> localDevices.addAll(names) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logW("HealthConnectRepository", e) {
                    "$label device scan failed; returning partial results"
                }
            }
            return localDevices
        }
    }
