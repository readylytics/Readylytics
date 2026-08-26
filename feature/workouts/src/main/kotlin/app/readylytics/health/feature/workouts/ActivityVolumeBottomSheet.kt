package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.preferences.UnitSystem
import app.readylytics.health.core.scoring.domain.workouts.weekly.ActivityVolume

/** Full ranked list of per-type volume comparisons behind the section's "View all". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActivityVolumeBottomSheet(
    rows: List<ActivityVolume>,
    unitSystem: UnitSystem,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.activity_volume_title),
            style = MaterialTheme.typography.titleLarge,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
        )
        Spacer(Modifier.height(MaterialTheme.spacing.small))
        Column {
            rows.forEach { volume ->
                ActivityVolumeRow(volume = volume, unitSystem = unitSystem)
            }
        }
        Spacer(Modifier.height(MaterialTheme.spacing.small))
        Spacer(Modifier.navigationBarsPadding())
    }
}
