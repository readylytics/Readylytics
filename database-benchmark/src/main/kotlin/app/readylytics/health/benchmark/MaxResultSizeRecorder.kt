package app.readylytics.health.benchmark

import java.util.concurrent.atomic.AtomicLong

/**
 * Records the largest list an instrumented DAO call returned.
 *
 * `RoomDatabase.QueryCallback` cannot supply this: it observes the SQL statement and its bind
 * arguments, never the result. A "maximum result-set size" criterion therefore needs its own
 * instrument at the call site, and the two must not be collapsed into one — see
 * `MaxResultSizeRecorderTest.statementCountAndResultSizeAreDifferentInstruments`.
 *
 * Records sizes and labels only; never a row, a value, or a bound argument.
 */
class MaxResultSizeRecorder {
    private val max = AtomicLong(0)
    private val total = AtomicLong(0)

    @Volatile
    var largestLabel: String? = null
        private set

    val maxSize: Int get() = max.get().toInt()
    val totalRows: Long get() = total.get()

    fun reset() {
        max.set(0)
        total.set(0)
        largestLabel = null
    }

    suspend fun <T> record(
        label: String,
        block: suspend () -> List<T>,
    ): List<T> {
        val result = block()
        total.addAndGet(result.size.toLong())
        synchronized(this) {
            if (result.size > max.get()) {
                max.set(result.size.toLong())
                largestLabel = label
            }
        }
        return result
    }
}
