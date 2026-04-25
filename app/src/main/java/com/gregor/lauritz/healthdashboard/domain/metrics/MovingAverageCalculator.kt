package com.gregor.lauritz.healthdashboard.domain.metrics

object MovingAverageCalculator {
    fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
        } else {
            sorted[sorted.size / 2]
        }
    }

    fun median(values: List<Int>): Float {
        if (values.isEmpty()) return 0f
        val floats = values.map { it.toFloat() }
        return median(floats)
    }

    fun calculateMovingAverages(
        values: Map<Long, Float>,
        dateMs: Long,
        days7Ms: Long = 7 * 24 * 60 * 60 * 1000L,
        days30Ms: Long = 30 * 24 * 60 * 60 * 1000L,
    ): Pair<Float, Float> {
        val cutoff7d = dateMs - days7Ms
        val cutoff30d = dateMs - days30Ms

        val values7d = values.filter { (date, _) -> date >= cutoff7d }.values.toList()
        val values30d = values.filter { (date, _) -> date >= cutoff30d }.values.toList()

        return Pair(median(values7d), median(values30d))
    }
}
