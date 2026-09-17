package app.readylytics.health.core.model.domain.sync

data class BpmHistogram(
    val bins: Map<Int, Int> // bpm -> count
) {
    fun encode(): String {
        if (bins.isEmpty()) return "v1:"
        val entries = bins.entries.sortedBy { it.key }
        return "v1:" + entries.joinToString(",") { "${it.key}:${it.value}" }
    }

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

    fun percentile(p: Int): Int? {
        if (count == 0 || p !in 0..100) return null
        val targetCount = (count * p / 100.0).toInt().coerceAtMost(count - 1)
        var currentCount = 0
        var foundBpm: Int? = null
        for ((bpm, freq) in bins.entries.sortedBy { it.key }) {
            currentCount += freq
            if (currentCount > targetCount) {
                foundBpm = bpm
                break
            }
        }
        return foundBpm ?: bins.keys.maxOrNull()
    }

    companion object {
        fun decode(encoded: String): BpmHistogram {
            require(encoded.startsWith("v1:")) {
                "Unsupported histogram format: $encoded"
            }
            val data = encoded.substringAfter("v1:")
            if (data.isEmpty()) return BpmHistogram(emptyMap())
            
            val bins = data.split(",").associate {
                val parts = it.split(":")
                parts[0].toInt() to parts[1].toInt()
            }
            return BpmHistogram(bins)
        }
    }
}
