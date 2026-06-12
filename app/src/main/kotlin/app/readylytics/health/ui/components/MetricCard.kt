package app.readylytics.health.ui.components

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.readylytics.health.domain.model.MetricStatus

private val StaticEmptyLambda: () -> Unit = {}

@Composable
fun MetricCard(
    title: String,
    value: String,
    status: MetricStatus,
    tooltip: String,
    modifier: Modifier = Modifier,
    secondaryText: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val containerColor = status.containerColor()
    val contentColor = status.onContainerColor()

    Card(
        onClick = onClick ?: StaticEmptyLambda,
        enabled = onClick != null,
        modifier =
            modifier
                .height(156.dp)
                .let {
                    if (onClick != null) it.semantics { role = Role.Button } else it
                },
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(
                containerColor = containerColor,
                contentColor = contentColor,
                disabledContainerColor = containerColor,
                disabledContentColor = contentColor,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics { heading() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                MetricTooltip(description = tooltip, iconTint = contentColor)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.displaySmall,
                color = contentColor,
            )
            if (secondaryText != null) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.7f),
                )
            }
        }
    }
}
