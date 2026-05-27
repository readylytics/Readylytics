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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider

data class DataPointTooltipData(
    val valueText: String,
    val dateText: String,
    val offset: IntOffset = IntOffset(0, 0),
    // Optional third line rendered below dateText; null omits the row entirely.
    val extraLine: String? = null,
)

class TooltipCaretShape(
    private val caretHeightDp: Dp = 6.dp,
    private val caretWidthDp: Dp = 12.dp,
) : Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val caretHeight = with(density) { caretHeightDp.toPx() }
        val caretWidth = with(density) { caretWidthDp.toPx() }
        val path =
            Path().apply {
                val w = size.width
                val h = size.height
                val r = with(density) { 8.dp.toPx() } // Material 3 shapes.small is 8dp

                val bottomY = h - caretHeight

                // Draw rounded rect with caret at bottom
                moveTo(r, 0f)
                lineTo(w - r, 0f)
                quadraticTo(w, 0f, w, r)

                lineTo(w, bottomY - r)
                quadraticTo(w, bottomY, w - r, bottomY)

                val caretLeft = (w - caretWidth) / 2f
                val caretRight = caretLeft + caretWidth

                lineTo(caretRight, bottomY)
                lineTo(w / 2f, h) // tip pointing down
                lineTo(caretLeft, bottomY)

                lineTo(r, bottomY)
                quadraticTo(0f, bottomY, 0f, bottomY - r)

                lineTo(0f, r)
                quadraticTo(0f, 0f, r, 0f)
                close()
            }
        return Outline.Generic(path)
    }
}

class TooltipPopupPositionProvider(
    private val tapOffset: IntOffset,
    private val yOffsetPx: Int = 0,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // Horizontally center the popup above the tap point
        val x = anchorBounds.left + tapOffset.x - (popupContentSize.width / 2)
        // Position it always at the top of the diagram plus custom offset
        val y = anchorBounds.top + yOffsetPx

        // Keep popup on screen horizontally
        val maxX = windowSize.width - popupContentSize.width
        val clampedX = x.coerceIn(0, maxX)

        // Keep popup on screen vertically
        val clampedY = y.coerceIn(0, windowSize.height - popupContentSize.height)

        return IntOffset(clampedX, clampedY)
    }
}

@Composable
fun DataPointTooltip(
    isVisible: Boolean,
    data: DataPointTooltipData,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    yOffsetDp: Dp = 0.dp,
) {
    if (isVisible) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val yOffsetPx =
            remember(yOffsetDp, density) {
                with(density) { yOffsetDp.roundToPx() }
            }
        Popup(
            popupPositionProvider =
                remember(data.offset, yOffsetPx) {
                    TooltipPopupPositionProvider(data.offset, yOffsetPx)
                },
            onDismissRequest = onDismissRequest,
        ) {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(initialAlpha = 0.5f),
                exit = fadeOut(),
            ) {
                Surface(
                    shape = TooltipCaretShape(),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    modifier =
                        modifier
                            .widthIn(min = 70.dp, max = 150.dp)
                            .padding(horizontal = 8.dp),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier =
                            Modifier
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .padding(bottom = 6.dp), // extra padding to clear caret
                    ) {
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
                        data.extraLine?.let { extra ->
                            Text(
                                text = extra,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
            }
        }
    }
}
