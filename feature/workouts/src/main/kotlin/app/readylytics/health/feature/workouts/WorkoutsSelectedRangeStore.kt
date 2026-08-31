package app.readylytics.health.feature.workouts

import androidx.lifecycle.SavedStateHandle
import app.readylytics.health.core.ui.common.TimeRange
import javax.inject.Inject

/** Process-death survivor for the Workout tab's selected range (SavedStateHandle-backed). */
class WorkoutsSelectedRangeStore
    @Inject
    constructor(
        private val savedStateHandle: SavedStateHandle,
    ) {
        fun read(): TimeRange = savedStateHandle.get<TimeRange>(KEY) ?: TimeRange.SEVEN_DAYS

        fun write(range: TimeRange) {
            savedStateHandle[KEY] = range
        }

        private companion object {
            const val KEY = "selectedRange"
        }
    }
