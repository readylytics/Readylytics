package app.readylytics.health.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.R

/**
 * Reusable FAB for edit mode toggle that animates in/out based on edit state.
 *
 * This composable encapsulates the standard pattern for edit mode FAB:
 * - Appears with slide-up animation when entering edit mode
 * - Disappears with slide-down animation when exiting edit mode
 * - Provides two actions: Save (Done) and Cancel
 *
 * @param isVisible Whether edit mode is active
 * @param onDoneClick Callback when user clicks FAB to save and exit edit mode
 * @param onCancelClick Callback when user clicks FAB to cancel and exit edit mode
 * @param modifier Optional modifier for customization
 */
@Composable
fun EditModeFab(
    isVisible: Boolean,
    onDoneClick: () -> Unit,
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            SmallFloatingActionButton(
                onClick = onCancelClick,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_cancel_editing),
                )
            }

            ExtendedFloatingActionButton(
                onClick = onDoneClick,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = stringResource(R.string.action_done_editing),
                    )
                },
                text = {
                    Text(stringResource(R.string.action_done))
                },
            )
        }
    }
}
