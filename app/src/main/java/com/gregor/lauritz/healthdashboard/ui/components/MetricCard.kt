package com.gregor.lauritz.healthdashboard.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus

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
        modifier = modifier.let {
            if (onClick != null) it.semantics { role = Role.Button } else it
        },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor,
            disabledContentColor = contentColor,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor,
                )
                MetricTooltip(description = tooltip, iconTint = contentColor)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.displaySmall,
                color = contentColor,
            )
            if (secondaryText != null) {
                Spacer(modifier = Modifier.height(2.dp))
            }
            Text(
                text = secondaryText ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.7f),
            )
        }
    }
}
