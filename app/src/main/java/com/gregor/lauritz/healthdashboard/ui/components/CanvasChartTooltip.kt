package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput

data class SegmentHitBox(
    val index: Int,
    val xStart: Float,
    val xEnd: Float,
    val label: String,
)

fun Modifier.detectCanvasTap(
    segments: List<SegmentHitBox>,
    onSegmentTapped: (index: Int, label: String) -> Unit,
): Modifier =
    this.pointerInput(segments) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEvent.Type.Press) {
                    val position = event.changes[0].position
                    val tapX = position.x
                    val canvasWidth = size.width

                    // Scale segment x coordinates from [0,1] to actual canvas width
                    val scaledSegments =
                        segments.map { segment ->
                            segment.copy(
                                xStart = segment.xStart * canvasWidth,
                                xEnd = segment.xEnd * canvasWidth,
                            )
                        }

                    // Find which segment was tapped
                    val tappedSegment =
                        scaledSegments.find { segment ->
                            tapX >= segment.xStart && tapX <= segment.xEnd
                        }

                    if (tappedSegment != null) {
                        onSegmentTapped(tappedSegment.index, tappedSegment.label)
                    }
                }
            }
        }
    }
