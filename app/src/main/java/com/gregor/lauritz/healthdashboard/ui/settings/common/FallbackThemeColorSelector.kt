package com.gregor.lauritz.healthdashboard.ui.settings.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.R
import com.gregor.lauritz.healthdashboard.data.preferences.FallbackThemeColor

private fun FallbackThemeColor.labelRes(): Int =
    when (this) {
        FallbackThemeColor.BRAND_PURPLE -> R.string.fallback_color_brand_purple
        FallbackThemeColor.BRAND_BLUE -> R.string.fallback_color_brand_blue
        FallbackThemeColor.TURQUOISE -> R.string.fallback_color_turquoise
        FallbackThemeColor.GREEN -> R.string.fallback_color_green
        FallbackThemeColor.RECOVERY_BLUE -> R.string.fallback_color_recovery_blue
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
                    .horizontalScroll(rememberScrollState())
                    .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FallbackThemeColor.entries.forEach { option ->
                val isSelected = option == selectedColor
                val label = stringResource(option.labelRes())
                val selectedLabel = stringResource(R.string.fallback_color_selected, label)
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
                            ).selectable(
                                selected = isSelected,
                                onClick = { onColorSelected(option) },
                                role = Role.RadioButton,
                            ).semantics {
                                contentDescription = if (isSelected) selectedLabel else label
                            },
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

private fun contentColorFor(background: Color): Color {
    val luminance = 0.299 * background.red + 0.587 * background.green + 0.114 * background.blue
    return if (luminance > 0.5) Color.Black else Color.White
}
