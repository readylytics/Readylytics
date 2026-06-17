package app.readylytics.health.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp

@Immutable
enum class GaugeComparisonTone {
    POSITIVE,
    NEGATIVE,
    NEUTRAL,
}

@Composable
fun GaugeComparisonChip(
    text: String,
    tone: GaugeComparisonTone,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val accentColor =
        when (tone) {
            GaugeComparisonTone.POSITIVE -> colorScheme.primary
            GaugeComparisonTone.NEGATIVE -> colorScheme.error
            GaugeComparisonTone.NEUTRAL -> colorScheme.onSurfaceVariant
        }

    Surface(
        modifier = modifier.defaultMinSize(minHeight = 28.dp),
        shape = CircleShape,
        color = accentColor.copy(alpha = 0.14f).compositeOver(colorScheme.surfaceContainerHigh),
        contentColor = accentColor,
    ) {
        Text(
            text = text,
            modifier =
                Modifier.padding(
                    PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}
