package app.readylytics.health.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing

@Composable
fun PrivacyRationaleContent(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(MaterialTheme.spacing.pageSectionGapLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.privacy_rationale_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))
        Text(
            text = stringResource(R.string.privacy_rationale_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MaterialTheme.spacing.extraLarge))
        Button(onClick = onBackClick) {
            Text(stringResource(app.readylytics.health.core.ui.R.string.back))
        }
    }
}
