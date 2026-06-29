package app.readylytics.health.feature.vitals.heartrate

import app.readylytics.health.core.ui.model.HrSample
import java.time.LocalDate

data class ZoneTotal(
    val durationMs: Long,
    val percent: Float,
    val formattedPercent: String,
)

data class HeartRateDetailUiState(
    val samples: List<HrSample> = emptyList(),
    val minBpm: Int? = null,
    val maxBpm: Int? = null,
    val avgBpm: Int? = null,
    val zoneTotals: Map<Int, ZoneTotal> = emptyMap(),
    val selectedDate: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
    val isLoading: Boolean = true,
    val zone1MinBpm: Int = 95,
    val zone1MaxBpm: Int = 114,
    val zone2MaxBpm: Int = 133,
    val zone3MaxBpm: Int = 152,
    val zone4MaxBpm: Int = 171,
)
