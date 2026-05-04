package com.gregor.lauritz.healthdashboard.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
import com.gregor.lauritz.healthdashboard.domain.circadian.CircadianThresholdDefaults
import com.gregor.lauritz.healthdashboard.domain.circadian.CircadianThresholdValue

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

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Circadian Consistency",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Show error state (Issue #8)
            if (error != null) {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = onErrorDismissed,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.heightIn(min = 32.dp)
                        ) {
                            Text("Dismiss", style = MaterialTheme.typography.labelMedium)
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
                )
            }
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(
                checked = !useStandardRollingAnchor,
                onCheckedChange = { onModeChanged(!it) },
                modifier = Modifier.semantics {
                    contentDescription = if (!useStandardRollingAnchor) {
                        "Within-week regularity mode enabled. Compares sleep consistency on same day-of-week across different weeks."
                    } else {
                        "Within-week regularity mode disabled"
                    }
                }
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Within-Week Regularity (Recommended)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Compares same day-of-week across weeks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Standard rolling anchor option
        Row(
            modifier = Modifier
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
                    "Use Standard Rolling-Anchor",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "14-day rolling window (like other profiles)",
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Threshold Window",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Profile default: ${profileDefault}min",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = onReset,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Reset to default",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // Slider
        Slider(
            value = value,
            onValueChange = onValueChanged,
            valueRange = 0f..90f,
            steps = 8, // 0, 10, 20, 30, 40, 50, 60, 70, 80, 90
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Circadian threshold adjustment. Range: 0 to 90 minutes"
                    stateDescription = "Current value: ${value.toInt()} minutes"
                },
        )

        // Current value display
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "0 min",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Current: ${value.toInt()} min",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
            Text(
                "90 min",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Explanation
        Text(
            if (value.toInt() == profileDefault) {
                "Using profile default"
            } else {
                "Using custom setting"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

