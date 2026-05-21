package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup

data class DataPointTooltipData(
    val valueText: String,
    val dateText: String,
)

@Composable
fun DataPointTooltip(
    isVisible: Boolean,
    data: DataPointTooltipData,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isVisible) {
        Popup(
            onDismissRequest = onDismissRequest,
            alignment = androidx.compose.ui.Alignment.TopStart,
        ) {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(initialAlpha = 0.5f),
                exit = fadeOut(),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    modifier =
                        modifier
                            .widthIn(min = 140.dp, max = 200.dp)
                            .padding(horizontal = 8.dp),
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = data.valueText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                        )
                        Text(
                            text = data.dateText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.9f),
                        )
                    }
                }
            }
        }
    }
}
