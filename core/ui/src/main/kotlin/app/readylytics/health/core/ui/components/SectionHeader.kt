package app.readylytics.health.core.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
) {
    Text(
        text = title,
        style = style,
        color = if (enabled) color else color.copy(alpha = 0.5f),
        modifier =
            modifier.padding(
                horizontal = MaterialTheme.spacing.pageHorizontal,
                vertical = MaterialTheme.spacing.extraSmall,
            ),
    )
}
