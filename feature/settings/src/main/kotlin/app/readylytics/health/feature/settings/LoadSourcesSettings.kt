package app.readylytics.health.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.feature.settings.R
import app.readylytics.health.domain.scoring.LoadSourceMode

@Composable
fun LoadSourcesSection(
    uiState: SleepSettingsState,
    onEvent: (SettingsEvent) -> Unit,
) {
    Column {
        LoadSourcePicker(
            labelRes = R.string.load_sources_strain_label,
            selectedMode = uiState.strainLoadSourceMode,
            helpRes = R.string.load_sources_strain_help,
            onModeSelected = { onEvent(SettingsEvent.StrainLoadSourceModeChanged(it)) },
        )

        LoadSourcePicker(
            labelRes = R.string.load_sources_ras_label,
            selectedMode = uiState.rasSourceMode,
            helpRes = R.string.load_sources_ras_help,
            onModeSelected = { onEvent(SettingsEvent.RasSourceModeChanged(it)) },
        )

        Text(
            text = stringResource(R.string.load_sources_required_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Text(
            text = stringResource(R.string.load_sources_zone_caveat),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun LoadSourcePicker(
    labelRes: Int,
    selectedMode: LoadSourceMode,
    helpRes: Int,
    onModeSelected: (LoadSourceMode) -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.bodyLarge,
            )
        },
    )
    SingleChoiceSegmentedButtonRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
    ) {
        LoadSourceMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selectedMode == mode,
                onClick = { onModeSelected(mode) },
                shape =
                    SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = LoadSourceMode.entries.size,
                    ),
                label = {
                    Text(
                        text =
                            when (mode) {
                                LoadSourceMode.WORKOUT_ONLY ->
                                    stringResource(R.string.load_source_option_workout_only)
                                LoadSourceMode.EVERYDAY_HEART_RATE ->
                                    stringResource(R.string.load_source_option_everyday_hr)
                            },
                    )
                },
            )
        }
    }
    Text(
        text = stringResource(helpRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}


