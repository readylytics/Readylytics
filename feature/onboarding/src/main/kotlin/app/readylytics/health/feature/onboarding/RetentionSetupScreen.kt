package app.readylytics.health.feature.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.ui.components.settings.RetentionSlider

@Composable
fun RetentionSetupScreen(
    onContinueClick: (retentionDays: Int) -> Unit,
    onOpenSettingsClick: () -> Unit,
    requiredPermissions: Set<String>,
    optionalPermissions: Set<String>,
    modifier: Modifier = Modifier,
) {
    var retentionDays by remember { mutableIntStateOf(SettingsDefaults.RETENTION_DAYS) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(MaterialTheme.spacing.pageSectionGapLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
        ) {
            RetentionHeaderSection()
            Spacer(Modifier.height(MaterialTheme.spacing.large))
            RetentionSliderSection(
                retentionDays = retentionDays,
                onRetentionDaysChanged = { retentionDays = it },
            )
            Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapLarge))
            PermissionsSummary(
                requiredPermissions = requiredPermissions,
                optionalPermissions = optionalPermissions,
            )
        }

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))
        RetentionActionButtons(
            retentionDays = retentionDays,
            onContinueClick = onContinueClick,
            onOpenSettingsClick = onOpenSettingsClick,
        )
    }
}

@Composable
private fun RetentionHeaderSection() {
    Text(
        text = stringResource(R.string.onboarding_retention_title),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

    Text(
        text = stringResource(R.string.onboarding_retention_subtitle),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RetentionSliderSection(
    retentionDays: Int,
    onRetentionDaysChanged: (Int) -> Unit,
) {
    RetentionSlider(
        enabled = true,
        retentionDays = retentionDays,
        onEnabledChanged = {},
        onRetentionDaysChanged = onRetentionDaysChanged,
        showEnableToggle = false,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RetentionActionButtons(
    retentionDays: Int,
    onContinueClick: (retentionDays: Int) -> Unit,
    onOpenSettingsClick: () -> Unit,
) {
    Button(
        onClick = { onContinueClick(retentionDays) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.onboarding_grant_access))
    }

    Spacer(Modifier.height(MaterialTheme.spacing.small))

    TextButton(
        onClick = onOpenSettingsClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.onboarding_open_hc_settings))
    }
}

@Composable
private fun PermissionsSummary(
    requiredPermissions: Set<String>,
    optionalPermissions: Set<String>,
) {
    Text(
        text = stringResource(R.string.onboarding_hc_permissions_label),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(MaterialTheme.spacing.extraSmall))
    Text(
        text = stringResource(R.string.onboarding_hc_permissions_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(MaterialTheme.spacing.small))

    Text(
        text = stringResource(R.string.onboarding_required_permissions_label),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(MaterialTheme.spacing.extraSmall))
    requiredPermissions.mapNotNull { healthPermissionLabelRes(it) }.forEach { labelRes ->
        PermissionBulletRow(stringResource(labelRes))
    }

    if (optionalPermissions.isNotEmpty()) {
        Spacer(Modifier.height(MaterialTheme.spacing.small))
        Text(
            text = stringResource(R.string.onboarding_optional_permissions_label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(MaterialTheme.spacing.extraSmall))
        optionalPermissions.mapNotNull { healthPermissionLabelRes(it) }.forEach { labelRes ->
            PermissionBulletRow(stringResource(labelRes))
        }
    }
}
