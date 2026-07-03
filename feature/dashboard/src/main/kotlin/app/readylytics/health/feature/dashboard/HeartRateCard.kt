package app.readylytics.health.feature.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.readylytics.health.core.designsystem.dimens
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.components.MetricTooltip
import app.readylytics.health.core.ui.model.HeartRateDaySummary

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
                .height(MaterialTheme.dimens.cardHeight)
                .semantics { role = Role.Button },
        shape = MaterialTheme.shapes.large,
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
                    .padding(
                        horizontal = MaterialTheme.spacing.medium,
                        vertical = MaterialTheme.spacing.smallMedium,
                    ),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics { heading() },
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = stringResource(app.readylytics.health.core.ui.R.string.heart_rate_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                MetricTooltip(
                    description = stringResource(R.string.tooltip_heart_rate_card),
                    iconTint = contentColor,
                )
            }
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.extraSmall))
            if (summary == null) {
                Text(
                    text = stringResource(app.readylytics.health.core.ui.R.string.hr_no_data),
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
                    text = stringResource(app.readylytics.health.core.ui.R.string.hr_avg_display, summary.avgBpm),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.7f),
                )
            }
        }
    }
}
