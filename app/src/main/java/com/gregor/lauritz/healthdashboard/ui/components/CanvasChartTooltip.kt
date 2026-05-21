package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

data class SegmentHitBox(
    val index: Int,
    val xStart: Float,
    val xEnd: Float,
    val label: String,
)

fun Modifier.detectCanvasTap(
    segments: List<SegmentHitBox>,
    onSegmentTapped: (index: Int, label: String, tapOffset: androidx.compose.ui.geometry.Offset) -> Unit,
): Modifier =
    this.pointerInput(segments) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Press) {
                    val position = event.changes[0].position
                    val tapX = position.x
                    val canvasWidth = size.width

                    // Normalize tap position to [0,1] range
                    val relativeX = if (canvasWidth > 0) tapX / canvasWidth else 0f

                    // Find which segment was tapped
                    val tappedSegment =
                        segments.find { segment ->
                            relativeX >= segment.xStart && relativeX <= segment.xEnd
                        }

                    if (tappedSegment != null) {
                        val snappedX = ((tappedSegment.xStart + tappedSegment.xEnd) / 2f) * canvasWidth
                        val snappedOffset =
                            androidx.compose.ui.geometry
                                .Offset(snappedX, position.y)
                        onSegmentTapped(tappedSegment.index, tappedSegment.label, snappedOffset)
                    }
                }
            }
        }
    }
