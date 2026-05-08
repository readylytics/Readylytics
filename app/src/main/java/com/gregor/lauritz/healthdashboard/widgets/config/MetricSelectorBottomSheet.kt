package com.gregor.lauritz.healthdashboard.widgets.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.domain.model.MetricType

/**
 * Reusable bottom sheet for metric selection.
 * Used by all three configuration activities.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricSelectorBottomSheet(
    isVisible: Boolean,
    selectedMetric: MetricType? = null,
    onMetricSelected: (MetricType) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Select Metric",
) {
    if (!isVisible) return

    val sheetState = rememberModalBottomSheetState()
    val metrics = MetricType.values().toList()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(metrics) { metric ->
                    MetricSelectionItem(
                        metric = metric,
                        isSelected = metric == selectedMetric,
                        onClick = {
                            onMetricSelected(metric)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricSelectionItem(
    metric: MetricType,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = metric.displayName,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = metric.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
