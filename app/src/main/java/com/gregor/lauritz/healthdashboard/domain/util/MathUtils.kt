package com.gregor.lauritz.healthdashboard.domain.util

import kotlin.math.sqrt

/**
 * Extension functions for list math operations to improve readability and reusability.
 */

fun List<Float>.mean(): Float {
    if (isEmpty()) return 0f
    return average().toFloat()
}

@JvmName("medianFloat")
fun List<Float>.median(): Float {
    if (isEmpty()) return 0f
    val sorted = sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
}

@JvmName("medianInt")
fun List<Int>.median(): Float {
    if (isEmpty()) return 0f
    val sorted = sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid].toFloat()
}

fun List<Float>.stdev(): Float {
    if (size < 2) return 0f
    val avg = mean()
    // Bessel's correction (n-1) for sample standard deviation
    val variance = sumOf { ((it - avg) * (it - avg)).toDouble() }.toFloat() / (size - 1)
    return sqrt(variance)
}
