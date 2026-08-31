package app.readylytics.health.core.scoring.domain.util

import kotlin.math.sqrt

/**
 * Extension functions for list math operations to improve readability and reusability.
 */

fun List<Float>.mean(): Float = meanOrNull() ?: 0f

fun List<Float>.meanOrNull(): Float? = if (isEmpty()) null else average().toFloat()

@JvmName("medianFloat")
fun List<Float>.median(): Float = medianOrNull() ?: 0f

@JvmName("medianOrNullFloat")
fun List<Float>.medianOrNull(): Float? {
    if (isEmpty()) return null
    val sorted = sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
}

@JvmName("medianInt")
fun List<Int>.median(): Float = medianOrNull() ?: 0f

@JvmName("medianOrNullInt")
fun List<Int>.medianOrNull(): Float? {
    if (isEmpty()) return null
    val sorted = sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid].toFloat()
}

fun List<Float>.stdev(): Float = stdevOrNull() ?: 0f

fun List<Float>.stdevOrNull(): Float? {
    if (size < 2) return null
    val avg = mean()
    // Bessel's correction (n-1) for sample standard deviation
    val variance = sumOf { ((it - avg) * (it - avg)).toDouble() }.toFloat() / (size - 1)
    return sqrt(variance)
}

@JvmName("stdevInt")
fun List<Int>.stdev(): Float = stdevOrNull() ?: 0f

@JvmName("stdevOrNullInt")
fun List<Int>.stdevOrNull(): Float? {
    if (size < 2) return null
    val avg = average().toFloat()
    val variance = sumOf { ((it - avg) * (it - avg)).toDouble() }.toFloat() / (size - 1)
    return sqrt(variance)
}
