package app.readylytics.health.core.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import app.readylytics.health.core.designsystem.spacing

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier.padding(
                horizontal = MaterialTheme.spacing.pageHorizontal,
                vertical = MaterialTheme.spacing.extraSmall,
            ),
    ) {
        Text(
            text = title,
            style = style,
            color = if (enabled) color else color.copy(alpha = 0.5f),
            modifier = Modifier.weight(1f),
        )
        trailingContent?.invoke()
    }
}
