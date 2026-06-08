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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.R
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
                    text = stringResource(R.string.heart_rate_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                )
                MetricTooltip(
                    description = stringResource(R.string.tooltip_heart_rate_card),
                    iconTint = contentColor,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (summary == null) {
                Text(
                    text = stringResource(R.string.hr_no_data),
                    style = MaterialTheme.typography.headlineLarge,
                    color = contentColor,
                )
            } else {
                Text(
                    text = "${summary.minBpm}–${summary.maxBpm}",
                    style = MaterialTheme.typography.headlineLarge,
                    maxLines = 1,
                    color = contentColor,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.hr_avg_display, summary.avgBpm),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.7f),
                )
            }
        }
    }
}
