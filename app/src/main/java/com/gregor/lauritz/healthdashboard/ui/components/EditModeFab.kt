package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.gregor.lauritz.healthdashboard.R

/**
 * Reusable FAB for edit mode toggle that animates in/out based on edit state.
 *
 * This composable encapsulates the standard pattern for edit mode FAB:
 * - Appears with slide-up animation when entering edit mode
 * - Disappears with slide-down animation when exiting edit mode
 * - Provides accessibility labels via string resources
 *
 * @param isVisible Whether edit mode is active
 * @param onDoneClick Callback when user clicks FAB to exit edit mode
 * @param modifier Optional modifier for customization
 */
@Composable
fun EditModeFab(
    isVisible: Boolean,
    onDoneClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier,
    ) {
        ExtendedFloatingActionButton(
            onClick = onDoneClick,
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
