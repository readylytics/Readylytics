package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyResult
import com.gregor.lauritz.healthdashboard.domain.scoring.toStatus
import com.gregor.lauritz.healthdashboard.domain.scoring.toTimeString

@Composable
fun CircadianConsistencyCard(
    result: CircadianConsistencyResult,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val status = result.toStatus()
    val containerColor = status.containerColor()
    val contentColor = status.onContainerColor()

    val scoreText = when (result) {
        is CircadianConsistencyResult.Calibrating -> "Calibrating"
        is CircadianConsistencyResult.Ready -> "${result.score.toInt()}%"
    }
    val windowText = when (result) {
        is CircadianConsistencyResult.Calibrating -> null
        is CircadianConsistencyResult.Ready ->
            "Median: ${result.medianBedtimeMinutes.toTimeString()}→${result.medianWakeMinutes.toTimeString()}"
    }

    val tooltipText = remember(result) {
        val thresholdMinutes = when (result) {
            is CircadianConsistencyResult.Calibrating -> 30
            is CircadianConsistencyResult.Ready -> result.thresholdMinutes
        }
        circadianTooltipText(thresholdMinutes)
    }

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().semantics { role = Role.Button },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = containerColor,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Circadian Consistency",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor,
                    )
                    MetricTooltip(description = tooltipText, iconTint = contentColor)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = scoreText,
                    style = MaterialTheme.typography.displaySmall,
                    color = contentColor,
                )
                if (windowText != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = windowText,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.7f),
                    )
                }
            }
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = containerColor,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Circadian Consistency",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor,
                    )
                    MetricTooltip(description = tooltipText, iconTint = contentColor)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = scoreText,
                    style = MaterialTheme.typography.displaySmall,
                    color = contentColor,
                )
                if (windowText != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = windowText,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}
