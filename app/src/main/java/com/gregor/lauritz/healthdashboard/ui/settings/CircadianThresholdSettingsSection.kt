package com.gregor.lauritz.healthdashboard.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile

@Composable
fun CircadianThresholdSettingsSection(
    profile: PhysiologyProfile,
    currentOverride: Int?,
    isShiftWorkerMode: Boolean,
    onOverrideChanged: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val profileDefault = getProfileDefault(profile)
    var useStandardRollingAnchor by remember {
        mutableStateOf(currentOverride != null || profile != PhysiologyProfile.SHIFT_WORKER)
    }
    var thresholdValue by remember {
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
            Text(
                "Circadian Consistency",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

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
                TextButton(
                    onClick = onReset,
                    modifier = Modifier
                        .height(32.dp)
                        .padding(0.dp),
                ) {
                    Text(
                        "Reset",
                        style = MaterialTheme.typography.labelSmall,
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
            modifier = Modifier.fillMaxWidth(),
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

private fun getProfileDefault(profile: PhysiologyProfile): Int {
    return when (profile) {
        PhysiologyProfile.ATHLETE -> 20
        PhysiologyProfile.ACTIVE -> 30
        PhysiologyProfile.GENERAL -> 30
        PhysiologyProfile.SEDENTARY -> 45
        PhysiologyProfile.SHIFT_WORKER -> 20 // Fallback, shouldn't be used in within-week mode
    }
}
