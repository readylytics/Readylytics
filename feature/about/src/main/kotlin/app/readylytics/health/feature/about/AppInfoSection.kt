package app.readylytics.health.feature.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.feature.about.R

@Composable
fun AppInfoSection() {
    Column {
        SectionHeader(stringResource(R.string.about_header_scores))
        BodyText(stringResource(R.string.about_scores_intro))
        BodyText(stringResource(R.string.about_scores_honesty))

        HighlightBox {
            SubHeader(stringResource(R.string.about_measurement_header))
            BodyText(stringResource(R.string.about_measurement_text))
        }

        SectionDivider()

        SubHeader(stringResource(R.string.about_scores_at_glance_header))
        ScoreTable()
        BodyText(stringResource(R.string.about_scores_outro))
    }
}

@Composable
private fun ScoreTable() {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.pageHorizontal,
                    vertical = MaterialTheme.spacing.pageSectionGapSmall,
                ),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
    ) {
        ScoreTableContent()
    }
}

@Composable
private fun ScoreTableContent() {
    Column(
        modifier = Modifier.padding(MaterialTheme.spacing.small),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
    ) {
        ScoreTableHeader()
        HorizontalDivider(
            modifier = Modifier.padding(vertical = MaterialTheme.spacing.extraSmall),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        ScoreTableRow(
            stringResource(R.string.about_score_sleep),
            stringResource(R.string.about_score_sleep_answer),
            stringResource(R.string.about_score_range),
        )
        ScoreTableRow(
            stringResource(R.string.about_score_circadian),
            stringResource(R.string.about_score_circadian_answer),
            stringResource(R.string.about_score_range),
        )
        ScoreTableRow(
            stringResource(R.string.about_score_readiness),
            stringResource(R.string.about_score_readiness_answer),
            stringResource(R.string.about_score_range),
        )
        ScoreTableRow(
            stringResource(R.string.about_score_training_readiness),
            stringResource(R.string.about_score_training_readiness_answer),
            stringResource(R.string.about_score_range),
        )
    }
}

@Composable
private fun ScoreTableHeader() {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.about_score_table_score),
            Modifier.weight(1.5f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.about_score_table_answer),
            Modifier.weight(3f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.about_score_table_range),
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ScoreTableRow(
    col1: String,
    col2: String,
    col3: String,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.hairline)) {
        Text(parseMarkdown(col1), Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall)
        Text(col2, Modifier.weight(3f), style = MaterialTheme.typography.bodySmall)
        Text(col3, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}
