package app.readylytics.health.domain.sync

import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.sync.mappers.StepsMapper
import app.readylytics.health.domain.util.logD
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.yield
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads daily step counts from Health Connect, respecting the per-data-type source-device
 * selection. When a specific device is selected the aggregate API can't filter by device, so raw
 * records are read and aggregated by day after filtering; when "All devices" is selected the
 * aggregate API is used (it de-duplicates overlapping records across data origins).
 *
 * Used by [DailySyncUseCase] (recent window) and [ResyncRangeUseCase] (full historical range).
 */
@Singleton
class StepCountFetcher
    @Inject
    constructor(
        private val hcRepo: HealthConnectRepository,
    ) {
        private companion object {
            // Max concurrent Health Connect step reads during a catch-up sync.
            const val STEPS_FETCH_CONCURRENCY = 4
        }

        /**
         * Recent-window fetch for the last [windowDays] days ending [today]. When no device is
         * selected, day reads fan out concurrently (capped by a semaphore to avoid HC rate limiting
         * and memory pressure on large windows).
         */
        suspend fun fetchWindow(
            today: LocalDate,
            windowDays: Int,
            zoneId: ZoneId,
            stepsDevice: String?,
        ): Map<LocalDate, Long> {
            logD("StepCountFetcher") { "Bulk fetching steps for $windowDays days..." }
            val stepsMap = mutableMapOf<LocalDate, Long>()
            if (stepsDevice == null) {
                val stepsSemaphore = Semaphore(STEPS_FETCH_CONCURRENCY)
                coroutineScope {
                    val deferredSteps =
                        (0 until windowDays).map { i ->
                            val day = today.minusDays(i.toLong())
                            val dayStart = day.atStartOfDay(zoneId).toInstant()
                            val dayEnd = day.plusDays(1).atStartOfDay(zoneId).toInstant()
                            async {
                                stepsSemaphore.withPermit {
                                    day to retryWithBackoff { hcRepo.readSteps(dayStart, dayEnd) }
                                }
                            }
                        }
                    stepsMap.putAll(deferredSteps.awaitAll())
                }
            } else {
                val oldestTargetDay = today.minusDays((windowDays - 1).toLong())
                val windowStart = oldestTargetDay.atStartOfDay(zoneId).toInstant()
                val windowEnd = today.plusDays(1).atStartOfDay(zoneId).toInstant()
                val stepsRecords = retryWithBackoff { hcRepo.readStepsRecords(windowStart, windowEnd) }
                val stepEntries =
                    DeviceSourceFilter.filterToDevice(
                        StepsMapper.toStepEntries(stepsRecords),
                        stepsDevice,
                    ) { it.deviceName }
                stepsMap.putAll(
                    StepsMapper.sumByDay(stepEntries, zoneId),
                )
            }
            return stepsMap
        }

        /**
         * Full-range fetch for [startDate]..[endDate] used by the resync recompute phase. Reads are
         * wrapped in bounded backoff to ride out transient HC rate-limit / IO failures.
         */
        suspend fun fetchRange(
            startDate: LocalDate,
            endDate: LocalDate,
            chunkDays: Int,
            stepsDevice: String?,
            zoneId: ZoneId,
        ): Map<LocalDate, Long> {
            val stepsMap = mutableMapOf<LocalDate, Long>()
            if (startDate.isAfter(endDate)) return stepsMap

            if (stepsDevice == null) {
                // HC-003: one grouped-by-day aggregate call per chunk instead of one aggregate
                // call per calendar day -- a 10-year resync issues ~(range/chunkDays) HC calls
                // instead of ~3,650.
                var chunkStart = startDate
                while (!chunkStart.isAfter(endDate)) {
                    currentCoroutineContext().ensureActive()
                    val chunkEndExclusive = minOf(chunkStart.plusDays(chunkDays.toLong()), endDate.plusDays(1))
                    val windowStart = chunkStart.atStartOfDay(zoneId).toInstant()
                    val windowEnd = chunkEndExclusive.atStartOfDay(zoneId).toInstant()
                    stepsMap.putAll(
                        retryWithBackoff { hcRepo.readDailyStepTotals(windowStart, windowEnd, zoneId) },
                    )
                    chunkStart = chunkEndExclusive
                    yield()
                }
                return stepsMap
            }

            var chunkStart = startDate
            while (!chunkStart.isAfter(endDate)) {
                currentCoroutineContext().ensureActive()
                val chunkEndExclusive = minOf(chunkStart.plusDays(chunkDays.toLong()), endDate.plusDays(1))
                val stepsWindowStart = chunkStart.atStartOfDay(zoneId).toInstant()
                val stepsWindowEnd = chunkEndExclusive.atStartOfDay(zoneId).toInstant()
                val stepsRecords =
                    retryWithBackoff {
                        hcRepo.readStepsRecords(stepsWindowStart, stepsWindowEnd)
                    }
                val stepEntries =
                    DeviceSourceFilter.filterToDevice(
                        StepsMapper.toStepEntries(stepsRecords),
                        stepsDevice,
                    ) { it.deviceName }
                stepsMap.putAll(
                    StepsMapper.sumByDay(stepEntries, zoneId),
                )
                chunkStart = chunkEndExclusive
                yield()
            }
            return stepsMap
        }
    }
