package app.readylytics.health.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing

@Composable
fun SyncErrorScreen(
    errorMessage: String?,
    onRetry: () -> Unit,
    onReportIssue: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(MaterialTheme.spacing.pageSectionGapLarge)
                .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Warning,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.pageSectionGap))
        Text(
            text = stringResource(R.string.onboarding_sync_error_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))
        Text(
            text = errorMessage ?: stringResource(R.string.onboarding_sync_error_message),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.pageSectionGapLarge))
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.onboarding_sync_error_retry))
        }
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))
        OutlinedButton(
            onClick = onReportIssue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.onboarding_sync_error_report))
        }
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
        TextButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.onboarding_sync_error_skip))
        }
    }
}
