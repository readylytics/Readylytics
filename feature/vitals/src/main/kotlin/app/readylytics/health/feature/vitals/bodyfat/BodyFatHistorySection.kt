package app.readylytics.health.feature.vitals.bodyfat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.readylytics.health.core.designsystem.FitDashboardTheme
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.data.preferences.UnitSystem
import app.readylytics.health.core.model.domain.model.BodyFatCategory
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.ui.common.BodyFatHistoryItem
import app.readylytics.health.core.ui.components.HistoryCardLayout
import app.readylytics.health.core.ui.components.PaginationControls
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.feature.vitals.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import app.readylytics.health.core.ui.R as CoreUiR

@Composable
fun BodyFatHistorySection(
    items: List<BodyFatHistoryItem>,
    currentPage: Int,
    totalPages: Int,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeader(title = stringResource(CoreUiR.string.label_history))
        items.forEach { item ->
            BodyFatHistoryCard(
                item = item,
                modifier =
                    Modifier.padding(
                        horizontal = MaterialTheme.spacing.medium,
                        vertical = MaterialTheme.spacing.extraSmall,
                    ),
            )
        }
        PaginationControls(
            currentPage = currentPage,
            totalPages = totalPages,
            onPreviousPage = onPreviousPage,
            onNextPage = onNextPage,
        )
    }
}

@Composable
fun BodyFatHistoryCard(
    item: BodyFatHistoryItem,
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
    val bodyFatStr = "%.1f".format(item.bodyFatPercent)
    val subtitle =
        item.leanMassDisplay?.let { leanMass ->
            stringResource(
                R.string.body_fat_history_subtitle_with_lean_mass,
                bodyFatStr,
                "%.1f".format(leanMass),
                unitLabel,
            )
        } ?: stringResource(R.string.body_fat_history_subtitle_no_lean_mass, bodyFatStr)

    val pillLabelRes = bodyFatCategoryLabelRes(item.category)

    HistoryCardLayout(
        title = stringResource(R.string.body_fat_history_title_format, dateStr),
        subtitle = subtitle,
        pillLabel = stringResource(pillLabelRes),
        pillStatus = item.status,
        modifier = modifier,
    )
}

internal fun bodyFatCategoryLabelRes(category: BodyFatCategory): Int =
    when (category) {
        BodyFatCategory.BELOW_ESSENTIAL -> R.string.body_fat_category_below_essential
        BodyFatCategory.ESSENTIAL -> R.string.body_fat_category_essential
        BodyFatCategory.ATHLETIC -> R.string.body_fat_category_athletic
        BodyFatCategory.FITNESS -> R.string.body_fat_category_fitness
        BodyFatCategory.ACCEPTABLE -> R.string.body_fat_category_acceptable
        BodyFatCategory.OBESE -> R.string.body_fat_category_obese
        BodyFatCategory.BELOW_REFERENCE -> R.string.body_fat_category_below_reference
        BodyFatCategory.WITHIN_REFERENCE -> R.string.body_fat_category_within_reference
        BodyFatCategory.ABOVE_REFERENCE -> R.string.body_fat_category_above_reference
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
                        leanMassDisplay = 67.3f,
                        unitSystem = UnitSystem.METRIC,
                        status = MetricStatus.OPTIMAL,
                        category = BodyFatCategory.ATHLETIC,
                    ),
                modifier =
                    Modifier.padding(
                        horizontal = MaterialTheme.spacing.medium,
                        vertical = MaterialTheme.spacing.extraSmall,
                    ),
            )
            BodyFatHistoryCard(
                item =
                    BodyFatHistoryItem(
                        timestampMs = System.currentTimeMillis(),
                        bodyFatPercent = 22.5f,
                        leanMassDisplay = 64.1f,
                        unitSystem = UnitSystem.METRIC,
                        status = MetricStatus.NEUTRAL,
                        category = BodyFatCategory.ACCEPTABLE,
                    ),
                modifier =
                    Modifier.padding(
                        horizontal = MaterialTheme.spacing.medium,
                        vertical = MaterialTheme.spacing.extraSmall,
                    ),
            )
            BodyFatHistoryCard(
                item =
                    BodyFatHistoryItem(
                        timestampMs = System.currentTimeMillis(),
                        bodyFatPercent = 28.0f,
                        leanMassDisplay = null,
                        unitSystem = UnitSystem.METRIC,
                        status = MetricStatus.POOR,
                        category = BodyFatCategory.OBESE,
                    ),
                modifier =
                    Modifier.padding(
                        horizontal = MaterialTheme.spacing.medium,
                        vertical = MaterialTheme.spacing.extraSmall,
                    ),
            )
        }
    }
}
