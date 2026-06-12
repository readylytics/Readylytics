package app.readylytics.health.ui.bodyfat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.readylytics.health.R
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.ui.common.BodyFatHistoryItem
import app.readylytics.health.ui.components.HistoryCardLayout
import app.readylytics.health.ui.components.SectionHeader
import app.readylytics.health.ui.theme.FitDashboardTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun BodyFatHistorySection(
    items: List<BodyFatHistoryItem>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeader(title = stringResource(R.string.label_history))
        items.forEach { item ->
            BodyFatHistoryCard(
                item = item,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
fun BodyFatHistoryCard(
    item: BodyFatHistoryItem,
    modifier: Modifier = Modifier,
) {
    val dateStr =
        remember(item.timestampMs) {
            val fmt = DateTimeFormatter.ofPattern("(dd.MM)", Locale.getDefault())
            Instant.ofEpochMilli(item.timestampMs).atZone(ZoneId.systemDefault()).toLocalDate().format(fmt)
        }

    val bodyFatStr = "%.1f".format(item.bodyFatPercent)
    val subtitle =
        item.leanMassKg?.let { leanMass ->
            stringResource(R.string.body_fat_history_subtitle_with_lean_mass, bodyFatStr, "%.1f".format(leanMass))
        } ?: stringResource(R.string.body_fat_history_subtitle_no_lean_mass, bodyFatStr)

    val pillLabelRes =
        when (item.status) {
            MetricStatus.OPTIMAL -> R.string.body_fat_status_optimal
            MetricStatus.NEUTRAL -> R.string.body_fat_status_fitness
            MetricStatus.WARNING -> R.string.body_fat_status_average
            MetricStatus.POOR -> R.string.body_fat_status_above_range
            MetricStatus.NO_DATA, MetricStatus.CALIBRATING -> R.string.body_fat_status_calibrating
        }

    HistoryCardLayout(
        title = stringResource(R.string.body_fat_history_title_format, dateStr),
        subtitle = subtitle,
        pillLabel = stringResource(pillLabelRes),
        pillStatus = item.status,
        modifier = modifier,
    )
}

@Preview(showBackground = true)
@Composable
private fun BodyFatHistoryCardPreview() {
    FitDashboardTheme {
        Column {
            BodyFatHistoryCard(
                item =
                    BodyFatHistoryItem(
                        timestampMs = System.currentTimeMillis(),
                        bodyFatPercent = 14.2f,
                        leanMassKg = 67.3f,
                        status = MetricStatus.OPTIMAL,
                    ),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            BodyFatHistoryCard(
                item =
                    BodyFatHistoryItem(
                        timestampMs = System.currentTimeMillis(),
                        bodyFatPercent = 22.5f,
                        leanMassKg = 64.1f,
                        status = MetricStatus.NEUTRAL,
                    ),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            BodyFatHistoryCard(
                item =
                    BodyFatHistoryItem(
                        timestampMs = System.currentTimeMillis(),
                        bodyFatPercent = 28.0f,
                        leanMassKg = null,
                        status = MetricStatus.POOR,
                    ),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}
