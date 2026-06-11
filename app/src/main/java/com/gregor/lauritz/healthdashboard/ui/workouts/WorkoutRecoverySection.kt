package com.gregor.lauritz.healthdashboard.ui.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.R
import com.gregor.lauritz.healthdashboard.ui.components.MetricTooltip

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun WorkoutRecoverySection(uiState: WorkoutDetailUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = stringResource(R.string.workout_recovery_header),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                MetricTooltip(
                    description = stringResource(R.string.workout_recovery_tooltip_description),
                    iconTint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            HrrItem(stringResource(R.string.workout_recovery_item_1min), uiState.hrr1Min, 18)
            HrrItem(stringResource(R.string.workout_recovery_item_2min), uiState.hrr2Min, 35)
            HrrItem(stringResource(R.string.workout_recovery_item_3min), uiState.hrr3Min, null)
        }
    }
}

@Composable
private fun HrrItem(
    label: String,
    drop: Int?,
    threshold: Int?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (drop != null) {
            val color =
                if ((threshold != null) &&
                    (drop >= threshold)
                ) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            Text(
                text = stringResource(R.string.workout_recovery_bpm_value, drop),
                style = MaterialTheme.typography.bodyLarge,
                color = color,
            )
        } else {
            Text(
                text = stringResource(R.string.workout_recovery_na),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
