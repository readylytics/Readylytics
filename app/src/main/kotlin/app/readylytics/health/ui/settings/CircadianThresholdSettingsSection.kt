package app.readylytics.health.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    isShiftWorkerMode: Boolean,
    onOverrideChanged: (Int?) -> Unit,
    isLoading: Boolean = false,
    error: String? = null,
    onErrorDismissed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val profileDefault = CircadianThresholdDefaults.getProfileDefault(profile)
    var useStandardRollingAnchor by rememberSaveable {
        mutableStateOf(currentOverride != null || profile != PhysiologyProfile.SHIFT_WORKER)
    }
    var thresholdValue by rememberSaveable(currentOverride) {
        mutableStateOf((currentOverride ?: profileDefault).toFloat())
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

        if (profile == PhysiologyProfile.SHIFT_WORKER) {
            // Shift worker mode selection
            ShiftWorkerModeSelector(
                useStandardRollingAnchor = useStandardRollingAnchor,
                profileDefault = profileDefault,
                onModeChanged = { useStandard ->
                    useStandardRollingAnchor = useStandard
                    if (!useStandard) {
                        // Clear override when switching to within-week mode
                        onOverrideChanged(null)
                        thresholdValue = profileDefault.toFloat()
                    }
                },
                modifier = Modifier.padding(horizontal = SettingsConstants.HORIZONTAL_PADDING),
            )

            // Show slider only if using standard rolling anchor
            if (useStandardRollingAnchor) {
                Spacer(modifier = Modifier.height(8.dp))
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
        } else {
            // Regular user mode - show slider with profile default
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
}

@Composable
private fun ShiftWorkerModeSelector(
    useStandardRollingAnchor: Boolean,
    profileDefault: Int,
    onModeChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Within-week mode option
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(
                checked = !useStandardRollingAnchor,
                onCheckedChange = { onModeChanged(!it) },
                modifier =
                    Modifier.semantics {
                        contentDescription =
                            if (!useStandardRollingAnchor) {
                                "Within-week regularity mode enabled. Compares sleep consistency on same day-of-week across different weeks."
                            } else {
                                "Within-week regularity mode disabled"
                            }
                    },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.circadian_within_week_mode_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.circadian_within_week_mode_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Standard rolling anchor option
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(
                checked = useStandardRollingAnchor,
                onCheckedChange = onModeChanged,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.circadian_rolling_anchor_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.circadian_rolling_anchor_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
        val stateDesc = stringResource(R.string.accessibility_circadian_threshold_state, value.toInt())

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
            Text(stringResource(R.string.profile_title_general), style = MaterialTheme.typography.titleSmall)
            CircadianThresholdSettingsSection(
                profile = PhysiologyProfile.GENERAL,
                currentOverride = null,
                isShiftWorkerMode = false,
                onOverrideChanged = {},
            )

            HorizontalDivider()

            Text(stringResource(R.string.profile_title_shift_worker), style = MaterialTheme.typography.titleSmall)
            CircadianThresholdSettingsSection(
                profile = PhysiologyProfile.SHIFT_WORKER,
                currentOverride = 30,
                isShiftWorkerMode = true,
                onOverrideChanged = {},
            )
        }
    }
}
