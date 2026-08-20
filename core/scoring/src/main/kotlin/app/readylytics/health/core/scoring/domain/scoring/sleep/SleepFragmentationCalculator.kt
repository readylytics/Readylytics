package app.readylytics.health.core.scoring.domain.scoring.sleep

import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepFragmentationCalculator

import app.readylytics.health.core.model.domain.scoring.ScoringConstants

import app.readylytics.health.domain.model.SleepStageType
import app.readylytics.health.domain.repository.SleepStageData
import app.readylytics.health.core.model.domain.scoring.ScoringConstants.Sleep

/**
 * Wake After Sleep Onset (WASO) and discrete awakening count for one sleep session.
 * Bounded by true sleep onset and final awakening, so pre-sleep and post-wake time in bed
 * is not counted as fragmentation.
 */
data class SleepFragmentation(
    val wasoMinutes: Float,
    val awakeningCount: Int,
) {
    companion object {
        val NONE = SleepFragmentation(wasoMinutes = 0f, awakeningCount = 0)
    }
}

object SleepFragmentationCalculator {
    fun compute(stages: List<SleepStageData>): SleepFragmentation {
        val asleep =
            stages.filter {
                it.stageType != SleepStageType.AWAKE.value && it.stageType != SleepStageType.UNKNOWN.value
            }
        if (asleep.isEmpty()) return SleepFragmentation.NONE

        val onset = asleep.minOf { it.startTime }
        val finalAwakening = asleep.maxOf { it.endTime }

        val windows =
            stages
                .filter { it.stageType == SleepStageType.AWAKE.value }
                .mapNotNull { stage ->
                    val start = stage.startTime.coerceAtLeast(onset)
                    val end = stage.endTime.coerceAtMost(finalAwakening)
                    if (end > start) start to end else null
                }

        val merged = mergeOverlapping(windows)
        val totalMs = merged.sumOf { it.second - it.first }
        val awakenings = merged.count { it.second - it.first >= Sleep.MIN_AWAKENING_DURATION_MS }

        return SleepFragmentation(
            wasoMinutes = totalMs / 60_000f,
            awakeningCount = awakenings,
        )
    }

    private fun mergeOverlapping(windows: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        if (windows.isEmpty()) return emptyList()
        val sorted = windows.sortedBy { it.first }
        val merged = mutableListOf(sorted.first())
        for (window in sorted.drop(1)) {
            val last = merged.last()
            if (window.first <= last.second) {
                merged[merged.lastIndex] = last.first to maxOf(last.second, window.second)
            } else {
                merged.add(window)
            }
        }
        return merged
    }
}
