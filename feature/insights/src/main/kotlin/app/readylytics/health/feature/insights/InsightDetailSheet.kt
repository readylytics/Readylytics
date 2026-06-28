package app.readylytics.health.feature.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.readylytics.health.domain.insights.detail.InsightConfidence
import app.readylytics.health.domain.insights.detail.InsightDetailContent
import app.readylytics.health.domain.insights.detail.InsightDetailType
import app.readylytics.health.feature.insights.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightDetailSheet(
    content: InsightDetailContent,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = content.title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = content.cardDescription,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Section(content.observedSignalTitle, content.observedSignal)
            val meaningTitle = content.meaningTitle
            val meaning = content.meaning
            if (meaningTitle != null && meaning != null) {
                Section(meaningTitle, meaning)
            }
            val confidence = content.confidence
            if (content.type == InsightDetailType.PHYSIOLOGY && confidence != null) {
                val confidenceRes =
                    when (confidence) {
                        InsightConfidence.LOW -> R.string.confidence_low
                        InsightConfidence.LOW_MEDIUM -> R.string.confidence_low_medium
                        InsightConfidence.MEDIUM -> R.string.confidence_medium
                        InsightConfidence.MEDIUM_HIGH -> R.string.confidence_medium_high
                        InsightConfidence.HIGH -> R.string.confidence_high
                    }
                Section(
                    title = stringResource(R.string.insight_detail_confidence),
                    body = stringResource(confidenceRes),
                )
            }
            if (content.causes.isNotEmpty()) {
                val causesTitle = content.causesTitle
                CauseSection(causesTitle, content.causes.map { "${it.title}: ${it.description}" })
            }
            if (content.recommendations.isNotEmpty()) {
                ListSection(content.recommendationsTitle, content.recommendations)
            }
            val caveatsTitle = content.caveatsTitle
            if (caveatsTitle != null && content.caveats.isNotEmpty()) {
                ListSection(caveatsTitle, content.caveats)
            }
            val safetyNote = content.safetyNote
            if (!safetyNote.isNullOrBlank()) {
                Section(stringResource(R.string.insight_detail_safety_note), safetyNote)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Section(
    title: String,
    body: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(text = body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ListSection(
    title: String,
    values: List<String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        values.forEach { value ->
            Text(text = "• $value", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CauseSection(
    title: String,
    values: List<String>,
) {
    ListSection(title = title, values = values)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
