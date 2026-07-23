package app.readylytics.health.data.local

import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.domain.repository.TransactionRunner
import app.readylytics.health.domain.sync.HealthIngestionBatch
import app.readylytics.health.domain.sync.HeartRateInput
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals

class PersistenceBatchingTest {
    @Test
    fun `large sample lists are split into bounded persistence batches`() =
        runTest {
            val batchSizes = mutableListOf<Int>()

            (1..12_001).toList().forEachPersistenceBatch { batch ->
                batchSizes += batch.size
            }

            assertEquals(listOf(5_000, 5_000, 2_001), batchSizes)
        }

    @Test
    fun `store persists metadata before bounded heart rate transactions`() =
        runTest {
            val events = mutableListOf<String>()
            val transactionRunner = RecordingTransactionRunner(events)
            val heartRateDao = recordingDao<HeartRateDao>(events, "heartRate")
            val store =
                RoomHealthIngestionStore(
                    sleepSessionDao = recordingDao(events, "sleep"),
                    sleepStageDao = recordingDao(events, "sleepStage"),
                    heartRateDao = heartRateDao,
                    hrvDao = recordingDao(events, "hrv"),
                    workoutDao = recordingDao(events, "workout"),
                    weightRecordDao = recordingDao(events, "weight"),
                    bodyFatRecordDao = recordingDao(events, "bodyFat"),
                    bloodPressureRecordDao = recordingDao(events, "bloodPressure"),
                    oxygenSaturationRecordDao = recordingDao(events, "oxygen"),
                    stepRecordDao = recordingDao(events, "steps"),
                    dailySummaryDao = recordingDao(events, "summary"),
                    transactionRunner = transactionRunner,
                )

            store.persist(
                HealthIngestionBatch(
                    sleepSessions = emptyList(),
                    sleepStages = emptyList(),
                    heartRateSamples = (1..5_001).map(::heartRateInput),
                    hrvSamples = emptyList(),
                    workouts = emptyList(),
                    weights = emptyList(),
                    bodyFatSamples = emptyList(),
                    bloodPressureSamples = emptyList(),
                    oxygenSaturationSamples = emptyList(),
                    stepRecords = emptyList(),
                ),
            )

            assertEquals(3, transactionRunner.transactionCount)
            assertEquals(
                listOf("sleep:0", "heartRate:5000", "heartRate:1"),
                events.filter {
                    it.startsWith("sleep:") ||
                        it.startsWith("heartRate:")
                },
            )
        }

    @Test
    fun `cancellation stops before next persistence batch`() =
        runTest {
            val batchSizes = mutableListOf<Int>()

            val job =
                launch {
                    (1..10_000).toList().forEachPersistenceBatch { batch ->
                        batchSizes += batch.size
                        cancel()
                    }
                }
            job.join()

            assertEquals(listOf(5_000), batchSizes)
        }

    private class RecordingTransactionRunner(
        private val events: MutableList<String>,
    ) : TransactionRunner {
        var transactionCount = 0
            private set

        override suspend fun <R> runInTransaction(block: suspend () -> R): R {
            transactionCount++
            events += "transaction:$transactionCount"
            return block()
        }
    }

    private inline fun <reified T> recordingDao(
        events: MutableList<String>,
        name: String,
    ): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            if (method.name == "upsertAll") {
                events += "$name:${(args?.firstOrNull() as? List<*>)?.size ?: 0}"
            }
            Unit
        } as T

    private fun heartRateInput(index: Int) =
        HeartRateInput(
            id = "hr-$index",
            timestampMs = index.toLong(),
            beatsPerMinute = 60,
            recordType = "SLEEP",
            sessionId = null,
            deviceName = null,
        )
}
