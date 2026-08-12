package app.readylytics.health.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import app.readylytics.health.core.designsystem.dimens
import app.readylytics.health.core.designsystem.spacing

@Composable
fun StepsCard(
    stepCount: Int?,
    stepGoal: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier =
                modifier
                    .fillMaxWidth()
                    .semantics { role = Role.Button },
            shape = MaterialTheme.shapes.large,
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
        ) {
            StepsCardContent(stepCount, stepGoal)
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
        ) {
            StepsCardContent(stepCount, stepGoal)
        }
    }
}

@Composable
private fun StepsCardContent(
    stepCount: Int?,
    stepGoal: Int,
) {
    Column(modifier = Modifier.padding(MaterialTheme.spacing.medium)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { heading() },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(app.readylytics.health.core.ui.R.string.label_daily_steps),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.smallMedium))
        val count = stepCount ?: 0
        val max = stepGoal / GOAL_FILL_CAP_FRACTION
        M3MetricBar(
            progressFraction = (count.toFloat() / max.coerceAtLeast(1f)).coerceIn(0f, 1f),
            activeColor = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.secondaryContainer,
            barHeight = MaterialTheme.dimens.miniBarHeight,
            markerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            showMarker = true,
            animateProgress = false,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${stepCount ?: 0}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.extraSmall))
            Text(
                text = "/ $stepGoal",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
