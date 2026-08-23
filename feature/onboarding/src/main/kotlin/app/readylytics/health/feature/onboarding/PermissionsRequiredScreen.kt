package app.readylytics.health.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.feature.onboarding.R
import app.readylytics.health.core.ui.R as CoreR

@Composable
fun PermissionsRequiredScreen(
    onRecheckPermissionsClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    missingPermissions: Set<String> = emptySet(),
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
        PermissionsHeader()
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
        PermissionsMessage()
        PermissionsList(missingPermissions = missingPermissions)
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapLarge))
        PermissionsActionButtons(
            onOpenSettingsClick = onOpenSettingsClick,
            onRecheckPermissionsClick = onRecheckPermissionsClick,
        )
    }
}

@Composable
private fun PermissionsHeader() {
    Text(
        text = stringResource(R.string.onboarding_permissions_required_title),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun PermissionsMessage() {
    Text(
        text = stringResource(R.string.onboarding_permissions_required_message),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PermissionsList(missingPermissions: Set<String>) {
    val missingLabelRes = missingPermissions.mapNotNull { healthPermissionLabelRes(it) }
    if (missingLabelRes.isNotEmpty()) {
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))
        Text(
            text = stringResource(R.string.onboarding_missing_permissions_label),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(MaterialTheme.spacing.extraSmall))
        missingLabelRes.forEach { labelRes ->
            PermissionBulletRow(stringResource(labelRes), modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PermissionsActionButtons(
    onOpenSettingsClick: () -> Unit,
    onRecheckPermissionsClick: () -> Unit,
) {
    Button(
        onClick = onOpenSettingsClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.onboarding_open_hc_settings))
    }

    Spacer(Modifier.height(MaterialTheme.spacing.small))

    TextButton(onClick = onRecheckPermissionsClick) {
        Text(stringResource(R.string.onboarding_recheck_permissions))
    }
}
