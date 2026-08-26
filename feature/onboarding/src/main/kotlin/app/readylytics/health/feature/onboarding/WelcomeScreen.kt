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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.feature.onboarding.R

@Composable
internal fun WelcomeScreen(
    onNext: () -> Unit,
    onRestoreFromBackupClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(MaterialTheme.spacing.pageSectionGapLarge)
                .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        WelcomeIcon()
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapLarge))
        WelcomeTitle()
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
        WelcomeSubtitle()
        Spacer(Modifier.height(MaterialTheme.spacing.extraLarge))
        WelcomeFeatureHighlights()
        Spacer(Modifier.height(MaterialTheme.spacing.extraLarge))
        WelcomePrivacyNote()
        Spacer(Modifier.height(MaterialTheme.spacing.extraLarge))
        WelcomeActionButtons(onNext = onNext, onRestoreFromBackupClick = onRestoreFromBackupClick)
    }
}

@Composable
private fun WelcomeIcon() {
    Icon(
        imageVector = Icons.Filled.Favorite,
        contentDescription = null,
        modifier = Modifier.size(80.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun WelcomeTitle() {
    Text(
        text = stringResource(R.string.onboarding_welcome_title),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun WelcomeSubtitle() {
    Text(
        text = stringResource(R.string.onboarding_welcome_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun WelcomePrivacyNote() {
    Text(
        text = stringResource(R.string.onboarding_privacy_note),
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun WelcomeActionButtons(
    onNext: () -> Unit,
    onRestoreFromBackupClick: () -> Unit,
) {
    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.onboarding_get_started))
    }

    Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

    TextButton(
        onClick = onRestoreFromBackupClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.onboarding_restore_backup_button))
    }
}

@Composable
private fun WelcomeFeatureHighlights() {
    FeatureItem(
        icon = { Icon(Icons.Filled.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = stringResource(R.string.onboarding_feature_sleep_title),
        description = stringResource(R.string.onboarding_feature_sleep_desc),
    )
    HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.spacing.extraSmall))
    FeatureItem(
        icon = {
            Icon(
                Icons.Filled.FavoriteBorder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
            )
        },
        title = stringResource(R.string.onboarding_feature_hrv_title),
        description = stringResource(R.string.onboarding_feature_hrv_desc),
    )
    HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.spacing.extraSmall))
    FeatureItem(
        icon = {
            Icon(
                Icons.Filled.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
            )
        },
        title = stringResource(R.string.onboarding_feature_training_title),
        description = stringResource(R.string.onboarding_feature_training_desc),
    )
}

@Composable
private fun FeatureItem(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
) {
    ListItem(
        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
        leadingContent = icon,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
    }
}
