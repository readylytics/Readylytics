package app.readylytics.health.feature.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Visual indicator showing whether cards are in edit/reorder mode
// Animates between primary and surface color schemes based on editing state
@Composable
fun EditModeIndicator(
    isEditing: Boolean,
    modifier: Modifier = Modifier,
) {
    val backgroundColor by animateColorAsState(
        targetValue =
            if (isEditing) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        label = "Edit mode background",
    )

    val contentColor by animateColorAsState(
        targetValue =
            if (isEditing) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        label = "Edit mode content",
    )

    Row(
        modifier =
            modifier
                .background(
                    color = backgroundColor,
                    shape = CircleShape,
                ).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isEditing) Icons.Filled.Check else Icons.Outlined.Edit,
            // Provide accessibility description for screen readers
            contentDescription = if (isEditing) "Currently in editing mode" else "Enter editing mode",
            tint = contentColor,
            modifier = Modifier.padding(2.dp),
        )
        Text(
            text = if (isEditing) "Editing" else "Edit",
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}
