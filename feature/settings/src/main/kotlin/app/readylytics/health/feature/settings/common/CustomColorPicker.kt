package app.readylytics.health.feature.settings.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import app.readylytics.health.core.designsystem.dimens
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.designsystem.toHexCode
import app.readylytics.health.data.preferences.FallbackThemeColor
import app.readylytics.health.feature.settings.R

private fun FallbackThemeColor.labelRes(): Int =
    when (this) {
        FallbackThemeColor.GREEN_PERFORMANCE -> R.string.fallback_color_green_performance
        FallbackThemeColor.BLUE_TRUST -> R.string.fallback_color_blue_trust
        FallbackThemeColor.PURPLE_INSIGHT -> R.string.fallback_color_purple_insight
        FallbackThemeColor.ICON_SIGNATURE -> R.string.fallback_color_icon_signature
        FallbackThemeColor.ICON_ELEMENTS -> R.string.fallback_color_icon_elements
    }

private fun contentColorFor(background: Color): Color =
    if (background.luminance() > 0.179f) Color.Black else Color.White

private fun parseHexColor(hex: String): Color? {
    val cleanHex = hex.trim().removePrefix("#")
    return try {
        if (cleanHex.length == 6) {
            Color("#FF$cleanHex".toColorInt())
        } else if (cleanHex.length == 8) {
            Color("#$cleanHex".toColorInt())
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}

@Composable
fun CustomColorPicker(
    label: String,
    selectedColor: Color,
    onColorSelected: (Color) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onReset: (() -> Unit)? = null,
    showPresets: Boolean = true,
) {
    val contentAlpha = if (enabled) 1.0f else 0.38f

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .alpha(contentAlpha),
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))

        if (showPresets) {
            // Presets Row
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .selectableGroup()
                        .alpha(contentAlpha),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smallMedium),
            ) {
                FallbackThemeColor.entries.forEach { option ->
                    val optionColor = Color(option.seedColor)
                    val isSelected = optionColor.toArgb() == selectedColor.toArgb()
                    val desc = stringResource(option.labelRes())
                    Box(
                        modifier =
                            Modifier
                                .size(MaterialTheme.dimens.avatarMedium)
                                .selectable(
                                    selected = isSelected && enabled,
                                    onClick = {
                                        if (enabled) {
                                            onColorSelected(optionColor)
                                        }
                                    },
                                    enabled = enabled,
                                    role = Role.RadioButton,
                                ).semantics {
                                    contentDescription = desc
                                },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(MaterialTheme.dimens.avatarSmall)
                                    .clip(CircleShape)
                                    .background(optionColor)
                                    .border(
                                        width = if (isSelected) MaterialTheme.dimens.borderSelected else 0.dp,
                                        color = MaterialTheme.colorScheme.outline,
                                        shape = CircleShape,
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = contentColorFor(optionColor),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))
        }

        // Hex Input Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smallMedium),
        ) {
            var textState by remember(selectedColor) {
                mutableStateOf(selectedColor.toHexCode())
            }

            OutlinedTextField(
                value = textState,
                onValueChange = { input ->
                    if (enabled) {
                        val filtered = input.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '#' }
                        if (filtered.length <= 9) {
                            textState = filtered
                            parseHexColor(filtered)?.let { color ->
                                if (color.toArgb() != selectedColor.toArgb()) {
                                    onColorSelected(color)
                                }
                            }
                        }
                    }
                },
                label = { Text(stringResource(R.string.color_picker_hex_code_label)) },
                placeholder = { Text(stringResource(R.string.color_picker_hex_code_placeholder)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )

            // Swatch Preview
            val swatchBorderAlpha = if (enabled) 1.0f else 0.38f
            val swatchBackgroundAlpha = if (enabled) 1.0f else 0.40f

            Box(
                modifier =
                    Modifier
                        .size(56.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(selectedColor.copy(alpha = selectedColor.alpha * swatchBackgroundAlpha))
                        .border(
                            width = MaterialTheme.dimens.borderThin,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = swatchBorderAlpha),
                            shape = MaterialTheme.shapes.medium,
                        ),
            )

            if (onReset != null && enabled) {
                IconButton(onClick = onReset) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.settings_reset_color_desc),
                    )
                }
            }
        }
    }
}
