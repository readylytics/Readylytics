package app.readylytics.health.feature.settings.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.readylytics.health.feature.settings.R
import app.readylytics.health.data.preferences.FallbackThemeColor

private fun FallbackThemeColor.labelRes(): Int =
    when (this) {
        FallbackThemeColor.GREEN_PERFORMANCE -> R.string.fallback_color_green_performance
        FallbackThemeColor.BLUE_TRUST -> R.string.fallback_color_blue_trust
        FallbackThemeColor.PURPLE_INSIGHT -> R.string.fallback_color_purple_insight
        FallbackThemeColor.ICON_SIGNATURE -> R.string.fallback_color_icon_signature
        FallbackThemeColor.ICON_ELEMENTS -> R.string.fallback_color_icon_elements
    }

@Composable
fun FallbackThemeColorSelector(
    selectedColor: FallbackThemeColor,
    onColorSelected: (FallbackThemeColor) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.fallback_theme_color_label),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState())
                    .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FallbackThemeColor.entries.forEach { option ->
                val isSelected = option == selectedColor
                val label = stringResource(option.labelRes())
                Box(
                    modifier =
                        Modifier
                            .size(48.dp)
                            .selectable(
                                selected = isSelected,
                                onClick = { onColorSelected(option) },
                                role = Role.RadioButton,
                            ).semantics {
                                contentDescription = label
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(option.seedColor))
                                .border(
                                    width = if (isSelected) 3.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                    shape = CircleShape,
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = contentColorFor(Color(option.seedColor)),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun contentColorFor(background: Color): Color =
    if (background.luminance() > 0.179f) Color.Black else Color.White


