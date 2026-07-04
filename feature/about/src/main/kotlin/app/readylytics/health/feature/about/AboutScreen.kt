package app.readylytics.health.feature.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.readylytics.health.core.designsystem.spacing

@Composable
fun AboutScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = MaterialTheme.spacing.pageBottom),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.pageSectionGap),
        ) {
            item { Spacer(Modifier.height(MaterialTheme.spacing.extraLarge)) }

            item { AppInfoSection() }

            item { ContributorsSection() }

            item { LicenseSection() }

            item { FeedbackSection(onDismiss = onDismiss) }
        }
    }
}
