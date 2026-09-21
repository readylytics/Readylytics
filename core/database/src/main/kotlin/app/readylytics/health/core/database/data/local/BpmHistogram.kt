package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.scoring.domain.util.weightedPercentileR7

/**
 * Versioned integer-frequency encoding of the plausible BPM domain for one source's contribution
 * to one minute (`hr_source_minute_contributions.bpmHistogram`). Count/sum/min/max/percentiles all
 * derive from the bins -- this is a lossless frequency table over integer BPM values, never a
 * merge of percentile sketches.
 *
 * Lives next to [MinuteBucketAggregator] on purpose: the two are the raw-list and histogram forms
 * of the same warm-tier aggregation and must agree, which is why [percentile] reuses
 * [weightedPercentileR7] -- the weighted equivalent of the `List<Int>.percentile` the aggregator
 * writes into `p5Bpm..p95Bpm` (see `BpmHistogramTest`'s equivalence test).
 */
data class BpmHistogram(
    /** Plausible integer BPM value → number of raw samples observed at that value. */
    val bins: Map<Int, Int>,
) {
    val count: Int
        get() = bins.values.sum()

    val sum: Long
        get() = bins.entries.sumOf { it.key.toLong() * it.value }

    val average: Double
        get() = if (count == 0) 0.0 else sum.toDouble() / count

    val min: Int?
        get() = bins.keys.minOrNull()

    val max: Int?
        get() = bins.keys.maxOrNull()

    fun encode(): String {
        if (bins.isEmpty()) return "$VERSION_PREFIX$ENCODING_VERSION:"
        return bins.entries
            .sortedBy { it.key }
            .joinToString(
                separator = ENTRY_SEPARATOR,
                prefix = "$VERSION_PREFIX$ENCODING_VERSION:",
            ) { "${it.key}$FIELD_SEPARATOR${it.value}" }
    }

    /**
     * R-type-7 linear-interpolation percentile over the expanded distribution, identical to
     * `expandedSamples.sorted().percentile(p / 100.0)`. [p] is a whole percent in `0..100`;
     * returns `null` for an empty histogram or an out-of-domain percentile.
     */
    fun percentile(p: Int): Int? {
        if (count == 0 || p !in 0..100) return null
        val sortedBins = bins.entries.sortedBy { it.key }
        return weightedPercentileR7(
            sortedValues = sortedBins.map { it.key }.toIntArray(),
            weights = sortedBins.map { it.value }.toIntArray(),
            p = p / 100.0,
        )
    }

    companion object {
        private const val VERSION_PREFIX = "v"
        private const val ENCODING_VERSION = 1
        private const val ENTRY_SEPARATOR = ","
        private const val FIELD_SEPARATOR = ":"
        private const val MIN_PLAUSIBLE_BPM = 1
        private const val MAX_PLAUSIBLE_BPM = 300

        /**
         * Parses an encoded histogram, raising the typed [BpmHistogramFormatException] for every
         * malformed input instead of leaking `IndexOutOfBoundsException`/`NumberFormatException`.
         */
        fun decode(encoded: String): BpmHistogram {
            val expectedPrefix = "$VERSION_PREFIX$ENCODING_VERSION:"
            if (!encoded.startsWith(expectedPrefix)) {
                throw BpmHistogramFormatException("Unsupported histogram encoding version")
            }
            val data = encoded.substring(expectedPrefix.length)
            if (data.isEmpty()) return BpmHistogram(emptyMap())
            val bins =
                data.split(ENTRY_SEPARATOR).associate { entry ->
                    val fields = entry.split(FIELD_SEPARATOR)
                    if (fields.size != 2) {
                        throw BpmHistogramFormatException("Histogram bin is not a bpm:count pair")
                    }
                    parseBin(fields[0], fields[1])
                }
            return BpmHistogram(bins)
        }

        private fun parseBin(
            bpmField: String,
            countField: String,
        ): Pair<Int, Int> {
            val bpm = bpmField.toIntOrNull()?.takeIf { it in MIN_PLAUSIBLE_BPM..MAX_PLAUSIBLE_BPM }
            val binCount = countField.toIntOrNull()?.takeIf { it >= 0 }
            if (bpm == null || binCount == null) {
                throw BpmHistogramFormatException(
                    "Histogram bin is not a plausible bpm:count pair with a non-negative count",
                )
            }
            return bpm to binCount
        }
    }
}

/** Typed error for a malformed or unsupported `bpmHistogram` payload. */
class BpmHistogramFormatException(
    message: String,
) : IllegalArgumentException(message)
