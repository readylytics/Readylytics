package app.readylytics.health.data.local

import app.readylytics.health.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.data.local.dao.BodyFatRecordDao
import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.HrvDao
import app.readylytics.health.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.data.local.dao.SleepSessionDao
import app.readylytics.health.data.local.dao.SleepStageDao
import app.readylytics.health.data.local.dao.WeightRecordDao
import app.readylytics.health.data.local.dao.WorkoutDao
import app.readylytics.health.domain.repository.TransactionRunner
import java.lang.reflect.Proxy
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * SCORE-002 regression lock: [RoomHealthIngestionStore.clearFrozenBaselines] must resolve day
 * boundaries with the caller-supplied zone (the stored scoring zone), never
 * [ZoneId.systemDefault]. Proven here by passing two zones 14 hours apart and asserting the
 * cleared range shifts by exactly that offset, regardless of the JVM's actual default zone.
 */
class RoomHealthIngestionStoreZoneTest {
    @Test
    fun `clearFrozenBaselines resolves boundaries from the passed zone, not the system default`() =
        runTest {
            val capturedFromMs = AtomicReference<Long>()
            val capturedToExclusiveMs = AtomicReference<Long>()
            val dailySummaryDao =
                recordingClearCallDao(capturedFromMs, capturedToExclusiveMs)
            val store = buildStore(dailySummaryDao)

            val start = LocalDate.of(2024, 6, 1)
            val endExclusive = LocalDate.of(2024, 6, 8)

            store.clearFrozenBaselines(start, endExclusive, ZoneId.of("UTC"))
            val utcFromMs = capturedFromMs.get()
            val utcToMs = capturedToExclusiveMs.get()

            store.clearFrozenBaselines(start, endExclusive, ZoneId.of("Pacific/Kiritimati"))
            val kiritimatiFromMs = capturedFromMs.get()
            val kiritimatiToMs = capturedToExclusiveMs.get()

            // Pacific/Kiritimati is UTC+14 year-round (no DST): its midnight occurs 14 hours
            // before UTC's midnight for the same calendar date.
            val fourteenHoursMs = Duration.ofHours(14).toMillis()
            assertEquals(utcFromMs - fourteenHoursMs, kiritimatiFromMs)
            assertEquals(utcToMs - fourteenHoursMs, kiritimatiToMs)
        }

    private fun buildStore(dailySummaryDao: DailySummaryDao): RoomHealthIngestionStore =
        RoomHealthIngestionStore(
            sleepSessionDao = noOpDao(),
            sleepStageDao = noOpDao(),
            heartRateDao = noOpDao(),
            hrvDao = noOpDao(),
            workoutDao = noOpDao(),
            weightRecordDao = noOpDao(),
            bodyFatRecordDao = noOpDao(),
            bloodPressureRecordDao = noOpDao(),
            oxygenSaturationRecordDao = noOpDao(),
            stepRecordDao = noOpDao(),
            dailySummaryDao = dailySummaryDao,
            transactionRunner =
                object : TransactionRunner {
                    override suspend fun <R> runInTransaction(block: suspend () -> R): R = block()
                },
        )

    private fun recordingClearCallDao(
        fromMsRef: AtomicReference<Long>,
        toExclusiveMsRef: AtomicReference<Long>,
    ): DailySummaryDao =
        Proxy.newProxyInstance(
            DailySummaryDao::class.java.classLoader,
            arrayOf(DailySummaryDao::class.java),
        ) { _, method, args ->
            if (method.name == "clearFrozenBaselinesBetween") {
                fromMsRef.set(args[0] as Long)
                toExclusiveMsRef.set(args[1] as Long)
            }
            Unit
        } as DailySummaryDao

    private inline fun <reified T> noOpDao(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> Unit } as T
}
