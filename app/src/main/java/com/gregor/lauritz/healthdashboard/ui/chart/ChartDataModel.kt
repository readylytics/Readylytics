package com.gregor.lauritz.healthdashboard.ui.chart

data class ChartPoint(
    val dateMs: Long,
    val value: Float,
    val avg7d: Float = 0f,
    val avg30d: Float = 0f,
)

data class ChartData(
    val points: List<ChartPoint>,
    val showTrends: Boolean = false,
    val yAxisMin: Float = 0f,
    val yAxisMax: Float = 100f,
)
