package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun DraggableCard(
    isEditing: Boolean = false,
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var offset by remember { mutableStateOf(IntOffset.Zero) }
    var isDragging by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .then(
                if (isEditing) {
                    Modifier
                        .offset { offset }
                        .graphicsLayer {
                            alpha = if (isDragging) 0.8f else 1f
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = {
                                    isDragging = true
                                    onDragStart()
                                },
                                onDragEnd = {
                                    isDragging = false
                                    onDragEnd()
                                    offset = IntOffset.Zero
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    offset += IntOffset(
                                        dragAmount.x.roundToInt(),
                                        dragAmount.y.roundToInt()
                                    )
                                }
                            )
                        }
                } else {
                    Modifier
                }
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isEditing) {
            Icon(
                imageVector = Icons.Outlined.DragIndicator,
                contentDescription = "Drag to reorder",
                modifier = Modifier
                    .size(24.dp)
                    .padding(start = 4.dp, end = 8.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            content()
        }

        if (isEditing && onRemove != null) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .size(40.dp)
                    .padding(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Remove card",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
