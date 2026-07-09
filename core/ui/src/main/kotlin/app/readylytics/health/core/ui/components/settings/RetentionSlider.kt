package app.readylytics.health.core.ui.components.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.R

@Composable
fun RetentionSlider(
    enabled: Boolean,
    retentionDays: Int,
    onEnabledChanged: (Boolean) -> Unit,
    onRetentionDaysChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showEnableToggle: Boolean = true,
) {
    var retentionMonths by remember(retentionDays) {
        mutableFloatStateOf(kotlin.math.round(retentionDays / 30f))
    }

    Column(modifier = modifier) {
        if (showEnableToggle) {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = {
                    Text(
                        stringResource(R.string.settings_retention_enabled_label),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                trailingContent = {
                    Switch(
                        checked = enabled,
                        onCheckedChange = onEnabledChanged,
                    )
                },
            )
        }

        AnimatedVisibility(visible = enabled) {
            Column(
                modifier =
                    Modifier.padding(
                        horizontal = MaterialTheme.spacing.medium,
                        vertical = MaterialTheme.spacing.extraSmall,
                    ),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.settings_retention_period_label),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text =
                            pluralStringResource(
                                R.plurals.settings_retention_months,
                                retentionMonths.toInt(),
                                retentionMonths.toInt(),
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = retentionMonths,
                    onValueChange = { retentionMonths = it },
                    onValueChangeFinished = {
                        onRetentionDaysChanged((retentionMonths.toInt() * 30))
                    },
                    valueRange = 3f..60f,
                    steps = 18,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.settings_retention_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MaterialTheme.spacing.extraSmall),
                )
            }
        }
    }
}
