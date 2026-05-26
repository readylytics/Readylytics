package com.gregor.lauritz.healthdashboard.ui.heartrate

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.ui.components.HrSparkline
import com.gregor.lauritz.healthdashboard.ui.components.MetricTooltip

@Composable
fun HeartRateCard(
    summary: HeartRateDaySummary?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = MaterialTheme.colorScheme.primaryContainer
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer

    Card(
        onClick = onClick,
        modifier =
            modifier
                .height(140.dp)
                .semantics { role = Role.Button },
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = containerColor,
                contentColor = contentColor,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Heart Rate",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                )
                MetricTooltip(
                    description = "Today's heart rate range across all activities. Tap to see full timeline and zone breakdown.",
                    iconTint = contentColor,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (summary == null) {
                Text(
                    text = "-- bpm",
                    style = MaterialTheme.typography.displaySmall,
                    color = contentColor,
                )
            } else {
                Text(
                    text = "${summary.minBpm} – ${summary.maxBpm}",
                    style = MaterialTheme.typography.displaySmall,
                    color = contentColor,
                )
                Text(
                    text = "bpm  ·  avg ${summary.avgBpm}",
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.7f),
                )
                Spacer(modifier = Modifier.weight(1f))
                HrSparkline(
                    hourlySamples = summary.hourlySamples,
                    modifier = Modifier.fillMaxWidth().height(30.dp),
                )
            }
        }
    }
}
