package com.gregor.lauritz.healthdashboard.ui.heartrate

import java.time.LocalDate

data class HrSample(
    val timeMs: Long,
    val bpm: Int,
    val zone: Int,
)

data class ZoneTotal(
    val durationMs: Long,
    val percent: Float,
)

data class HeartRateDaySummary(
    val minBpm: Int,
    val maxBpm: Int,
    val avgBpm: Int,
    val hourlySamples: List<Pair<Int, Int>>,
)

data class HeartRateDetailUiState(
    val samples: List<HrSample> = emptyList(),
    val minBpm: Int? = null,
    val maxBpm: Int? = null,
    val avgBpm: Int? = null,
    val zoneTotals: Map<Int, ZoneTotal> = emptyMap(),
    val selectedDate: LocalDate = LocalDate.now(),
    val isLoading: Boolean = true,
    val zone1MinBpm: Int = 95,
    val zone1MaxBpm: Int = 114,
    val zone2MaxBpm: Int = 133,
    val zone3MaxBpm: Int = 152,
    val zone4MaxBpm: Int = 171,
)
