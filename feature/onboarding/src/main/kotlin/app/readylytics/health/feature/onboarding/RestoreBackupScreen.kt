package app.readylytics.health.feature.onboarding

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import app.readylytics.health.core.ui.common.resolveOrNull
import app.readylytics.health.feature.onboarding.R
import app.readylytics.health.core.ui.R as CoreR
import app.readylytics.health.core.designsystem.spacing

@Composable
fun RestoreBackupScreen(
    state: OnboardingRestoreState,
    onRestoreClick: (uri: Uri, password: String) -> Unit,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var selectedUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var selectedFileName by rememberSaveable { mutableStateOf<String?>(null) }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    val filePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                selectedUri = uri
                selectedFileName = DocumentFile.fromSingleUri(context, uri)?.name
            }
        }

    val isBusy = state.isValidating || state.isRestoring
    val resolvedError = state.error.resolveOrNull()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(MaterialTheme.spacing.pageSectionGapLarge)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        IconButton(onClick = onBack, enabled = !isBusy) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(CoreR.string.back))
        }

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

        Text(
            text = stringResource(R.string.onboarding_restore_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

        Text(
            text = stringResource(R.string.onboarding_restore_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapLarge))

        OutlinedButton(
            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Description, contentDescription = null)
            Spacer(Modifier.width(MaterialTheme.spacing.pageSectionGapSmall))
            Text(selectedFileName ?: stringResource(R.string.onboarding_restore_select_file_button))
        }

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.onboarding_restore_password_label)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isBusy,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions =
                KeyboardActions(
                    onDone = {
                        selectedUri?.takeIf { password.isNotEmpty() && !isBusy }?.let {
                            onRestoreClick(it, password)
                        }
                    },
                ),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription =
                            stringResource(
                                if (showPassword) {
                                    CoreR.string.accessibility_password_hide
                                } else {
                                    CoreR.string.accessibility_password_show
                                },
                            ),
                    )
                }
            },
            singleLine = true,
        )

        if (resolvedError != null) {
            Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = resolvedError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismissError) {
                    Text(stringResource(CoreR.string.action_dismiss))
                }
            }
        }

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapLarge))

        Button(
            onClick = { selectedUri?.let { onRestoreClick(it, password) } },
            enabled = selectedUri != null && password.isNotEmpty() && !isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.onboarding_restore_button))
            }
        }
    }
}
