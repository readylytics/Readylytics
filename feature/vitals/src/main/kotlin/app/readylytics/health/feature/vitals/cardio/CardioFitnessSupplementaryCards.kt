package app.readylytics.health.feature.vitals.cardio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.scoring.domain.cardio.CooperCategory
import app.readylytics.health.feature.vitals.R
import java.util.Locale

/** Age/sex-specific Cooper norms band edges, with the user's current band highlighted. */
@Composable
internal fun CooperLadderCard(
    uiState: CardioFitnessDetailUiState,
    modifier: Modifier = Modifier,
) {
    val thresholds = uiState.thresholds ?: return
    val unit = stringResource(R.string.unit_ml_kg_min)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.medium)) {
            Text(
                text = stringResource(R.string.label_cooper_normative_ladder),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.small))

            val rows =
                listOf(
                    CooperCategory.SUPERIOR to
                        String.format(Locale.US, "≥ %.1f", thresholds.superior),
                    CooperCategory.EXCELLENT to
                        String.format(Locale.US, "%.1f – %.1f", thresholds.excellent, thresholds.superior),
                    CooperCategory.GOOD to
                        String.format(Locale.US, "%.1f – %.1f", thresholds.good, thresholds.excellent),
                    CooperCategory.FAIR to
                        String.format(Locale.US, "%.1f – %.1f", thresholds.fair, thresholds.good),
                    CooperCategory.POOR to
                        String.format(Locale.US, "< %.1f", thresholds.fair),
                )

            rows.forEach { (category, range) ->
                CooperLadderRow(category, range, unit, isCurrent = category == uiState.cooperCategory)
            }
        }
    }
}

@Composable
private fun CooperLadderRow(
    category: CooperCategory,
    range: String,
    unit: String,
    isCurrent: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.extraSmall),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(categoryLabelRes(category)),
            style = if (isCurrent) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "$range $unit",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Static card crediting Uth et al. (2004) and Health Connect as the VO2 Max data sources. */
@Composable
internal fun MethodologyCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.medium)) {
            Text(
                text = stringResource(R.string.label_scientific_methodology),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.small))
            Text(
                text = stringResource(R.string.cardio_fitness_methodology_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
