package app.readylytics.health.feature.workouts

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.Rowing
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.SportsGymnastics
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material.icons.filled.Stairs
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.ui.graphics.vector.ImageVector
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutType

/** Monochrome icon per activity type, tinted by the caller (`onSurfaceVariant`). Rendered in the
 *  same style as the Weekly stat cards' icons — no per-type accent colors. */
internal val WorkoutLayoutType.icon: ImageVector
    get() =
        when (this) {
            WorkoutLayoutType.RUNNING -> Icons.Filled.DirectionsRun
            WorkoutLayoutType.WALKING -> Icons.Filled.DirectionsWalk
            WorkoutLayoutType.CYCLING -> Icons.Filled.DirectionsBike
            WorkoutLayoutType.SWIMMING -> Icons.Filled.Pool
            WorkoutLayoutType.STRENGTH -> Icons.Filled.FitnessCenter
            WorkoutLayoutType.HIKING -> Icons.Filled.Hiking
            WorkoutLayoutType.YOGA -> Icons.Filled.SelfImprovement
            WorkoutLayoutType.PILATES -> Icons.Filled.SportsGymnastics
            WorkoutLayoutType.ELLIPTICAL -> Icons.Filled.FitnessCenter
            WorkoutLayoutType.ROWING -> Icons.Filled.Rowing
            WorkoutLayoutType.STAIRS -> Icons.Filled.Stairs
            WorkoutLayoutType.HIIT -> Icons.Filled.Whatshot
            WorkoutLayoutType.OTHER -> Icons.Filled.SportsScore
        }
