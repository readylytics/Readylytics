package app.readylytics.health.feature.vitals.weight

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.FitDashboardTheme
import app.readylytics.health.core.ui.common.WeightHistoryItem
import app.readylytics.health.core.ui.components.HistoryCardLayout
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.data.preferences.UnitSystem
import app.readylytics.health.domain.model.BmiStatus
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.toMetricStatus
import app.readylytics.health.feature.vitals.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import app.readylytics.health.core.ui.R as CoreUiR

@Composable
fun WeightHistorySection(
    items: List<WeightHistoryItem>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeader(title = stringResource(CoreUiR.string.label_history))
        items.forEach { item ->
            WeightHistoryCard(
                item = item,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
fun WeightHistoryCard(
    item: WeightHistoryItem,
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

    val unitLabel = if (item.unitSystem == UnitSystem.METRIC) "kg" else "lbs"
    val weightStr = "%.1f".format(item.weightDisplay)
    val subtitle =
        item.deltaDisplay?.let { delta ->
            val deltaStr = "%+.1f".format(delta)
            stringResource(R.string.weight_history_subtitle_with_delta, weightStr, unitLabel, deltaStr)
        } ?: stringResource(R.string.weight_history_subtitle_no_delta, weightStr, unitLabel)

    val pillLabelRes =
        when (item.bmiStatus) {
            BmiStatus.Optimal -> R.string.weight_status_normal
            BmiStatus.Neutral -> R.string.weight_status_overweight
            BmiStatus.Warning -> R.string.weight_status_obese_1
            BmiStatus.Poor -> R.string.weight_status_obese_2
            null -> R.string.weight_status_calibrating
        }
    val pillStatus = item.bmiStatus?.toMetricStatus() ?: MetricStatus.CALIBRATING

    HistoryCardLayout(
        title = stringResource(R.string.weight_history_title_format, dateStr),
        subtitle = subtitle,
        pillLabel = stringResource(pillLabelRes),
        pillStatus = pillStatus,
        modifier = modifier,
    )
}

@Preview(showBackground = true)
@Composable
private fun WeightHistoryCardPreview() {
    FitDashboardTheme {
        Column {
            WeightHistoryCard(
                item =
                    WeightHistoryItem(
                        timestampMs = System.currentTimeMillis(),
                        weightDisplay = 78.5f,
                        deltaDisplay = -0.4f,
                        unitSystem = UnitSystem.METRIC,
                        bmiStatus = BmiStatus.Optimal,
                    ),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            WeightHistoryCard(
                item =
                    WeightHistoryItem(
                        timestampMs = System.currentTimeMillis(),
                        weightDisplay = 92.1f,
                        deltaDisplay = 0.6f,
                        unitSystem = UnitSystem.METRIC,
                        bmiStatus = BmiStatus.Warning,
                    ),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            WeightHistoryCard(
                item =
                    WeightHistoryItem(
                        timestampMs = System.currentTimeMillis(),
                        weightDisplay = 65.0f,
                        deltaDisplay = null,
                        unitSystem = UnitSystem.METRIC,
                        bmiStatus = null,
                    ),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}
