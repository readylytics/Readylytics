package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.R

@Composable
fun SleepStageBreakdownRow(
    stageName: String,
    durationMinutes: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = color, shape = CircleShape),
        )

        Spacer(modifier = Modifier.width(8.dp))

        val hours = durationMinutes / 60
        val minutes = durationMinutes % 60
        val hoursStr = stringResource(R.string.time_unit_hours)
        val minutesStr = stringResource(R.string.time_unit_minutes)
        val durationText = if (hours > 0) {
            "$hours$hoursStr " + (if (minutes > 0) "$minutes$minutesStr" else "")
        } else {
            "$minutes$minutesStr"
        }

        Text(
            text = stringResource(R.string.sleep_breakdown_row_format, stageName, durationText),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
