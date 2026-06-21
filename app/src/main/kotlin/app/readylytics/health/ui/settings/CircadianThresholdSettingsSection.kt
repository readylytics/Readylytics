package app.readylytics.health.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.readylytics.health.R
import app.readylytics.health.data.preferences.PhysiologyProfile
import app.readylytics.health.domain.circadian.CircadianThresholdDefaults
import app.readylytics.health.ui.settings.common.SettingsConstants

private const val THRESHOLD_SLIDER_STEPS = 8 // Results in: 0, 10, 20, ..., 90 (Issue #9)

@Composable
fun CircadianThresholdSettingsSection(
    profile: PhysiologyProfile,
    currentOverride: Int?,
    onOverrideChanged: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    error: String? = null,
    onErrorDismissed: () -> Unit = {},
) {
    val profileDefault = CircadianThresholdDefaults.getProfileDefault(profile)
    var thresholdValue by remember(currentOverride) {
        mutableFloatStateOf((currentOverride ?: profileDefault).toFloat())
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SettingsConstants.VERTICAL_SPACER),
    ) {
        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Show error state (Issue #8)
        if (error != null) {
            ElevatedCard(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SettingsConstants.HORIZONTAL_PADDING, vertical = 4.dp),
                colors =
                    CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onErrorDismissed,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.heightIn(min = 32.dp),
                    ) {
                        Text(stringResource(R.string.action_dismiss), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // The override slider, defaulting to the active profile's threshold. Selecting a
        // profile implicitly sets the threshold; this is the only user knob.
        ThresholdSlider(
            value = thresholdValue,
            profileDefault = profileDefault,
            onValueChanged = { newValue ->
                thresholdValue = newValue
                onOverrideChanged(newValue.toInt())
            },
            onReset = {
                thresholdValue = profileDefault.toFloat()
                onOverrideChanged(null)
            },
            modifier = Modifier.padding(horizontal = SettingsConstants.HORIZONTAL_PADDING),
        )
    }
}

@Composable
private fun ThresholdSlider(
    value: Float,
    profileDefault: Int,
    onValueChanged: (Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Profile default label
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.circadian_threshold_window_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.circadian_profile_default, profileDefault),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = onReset,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.action_reset_to_default),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        val contentDesc = stringResource(R.string.accessibility_circadian_threshold_adjustment)
        val stateDesc =
            pluralStringResource(R.plurals.accessibility_circadian_threshold_state, value.toInt(), value.toInt())

        // Slider
        Slider(
            value = value,
            onValueChange = onValueChanged,
            valueRange = 0f..90f,
            steps = 8, // 0, 10, 20, 30, 40, 50, 60, 70, 80, 90
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = contentDesc
                        stateDescription = stateDesc
                    },
        )

        // Current value display
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.circadian_range_min),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.circadian_current_value, value.toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
            Text(
                stringResource(R.string.circadian_range_max),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Explanation
        Text(
            if (value.toInt() == profileDefault) {
                stringResource(R.string.circadian_using_profile_default)
            } else {
                stringResource(R.string.circadian_using_custom_setting)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
fun CircadianThresholdSettingsSectionPreview() {
    MaterialTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.circadian_preview_active_label), style = MaterialTheme.typography.titleSmall)
            CircadianThresholdSettingsSection(
                profile = PhysiologyProfile.ACTIVE,
                currentOverride = null,
                onOverrideChanged = {},
            )

            HorizontalDivider()

            Text(stringResource(R.string.circadian_preview_athlete_label), style = MaterialTheme.typography.titleSmall)
            CircadianThresholdSettingsSection(
                profile = PhysiologyProfile.ATHLETE,
                currentOverride = 30,
                onOverrideChanged = {},
            )
        }
    }
}
