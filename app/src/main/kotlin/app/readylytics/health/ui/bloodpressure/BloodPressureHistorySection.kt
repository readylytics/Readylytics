package app.readylytics.health.ui.bloodpressure

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.readylytics.health.R
import app.readylytics.health.core.ui.common.BloodPressureHistoryItem
import app.readylytics.health.core.ui.components.HistoryCardLayout
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.domain.model.BloodPressureStatus
import app.readylytics.health.domain.model.toMetricStatus
import app.readylytics.health.ui.theme.FitDashboardTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun BloodPressureHistorySection(
    items: List<BloodPressureHistoryItem>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeader(title = stringResource(R.string.label_history))
        items.forEach { item ->
            BloodPressureHistoryCard(
                item = item,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
fun BloodPressureHistoryCard(
    item: BloodPressureHistoryItem,
    modifier: Modifier = Modifier,
) {
    val dateStr =
        remember(item.timestampMs) {
            val fmt = DateTimeFormatter.ofPattern("dd.MM", Locale.getDefault())
            Instant
                .ofEpochMilli(item.timestampMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .format(fmt)
        }

    val pillLabelRes =
        when (item.status) {
            BloodPressureStatus.Optimal -> R.string.bp_status_normal
            BloodPressureStatus.Neutral -> R.string.bp_status_elevated
            BloodPressureStatus.HypertensionStage1 -> R.string.bp_status_stage1
            BloodPressureStatus.HypertensionStage2 -> R.string.bp_status_stage2
        }

    HistoryCardLayout(
        title = stringResource(R.string.bp_history_title_format, dateStr),
        subtitle = stringResource(R.string.bp_history_subtitle_format, item.systolic, item.diastolic),
        pillLabel = stringResource(pillLabelRes),
        pillStatus = item.status.toMetricStatus(),
        modifier = modifier,
    )
}

@Preview(showBackground = true)
@Composable
private fun BloodPressureHistoryCardPreview() {
    FitDashboardTheme {
        Column {
            BloodPressureHistoryCard(
                item =
                    BloodPressureHistoryItem(
                        timestampMs = System.currentTimeMillis(),
                        systolic = 115,
                        diastolic = 75,
                        status = BloodPressureStatus.Optimal,
                    ),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            BloodPressureHistoryCard(
                item =
                    BloodPressureHistoryItem(
                        timestampMs = System.currentTimeMillis(),
                        systolic = 125,
                        diastolic = 78,
                        status = BloodPressureStatus.Neutral,
                    ),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            BloodPressureHistoryCard(
                item =
                    BloodPressureHistoryItem(
                        timestampMs = System.currentTimeMillis(),
                        systolic = 134,
                        diastolic = 86,
                        status = BloodPressureStatus.HypertensionStage1,
                    ),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            BloodPressureHistoryCard(
                item =
                    BloodPressureHistoryItem(
                        timestampMs = System.currentTimeMillis(),
                        systolic = 150,
                        diastolic = 96,
                        status = BloodPressureStatus.HypertensionStage2,
                    ),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}
