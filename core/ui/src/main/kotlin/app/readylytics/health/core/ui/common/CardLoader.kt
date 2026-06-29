package app.readylytics.health.core.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Displays either a skeleton loader or actual card content based on loading state.
 * Eliminates repetitive if-loading patterns in card factories.
 *
 * @param isLoading true to display skeleton, false to display content
 * @param skeleton Composable to display when loading
 * @param content Composable to display when not loading
 * @param modifier Modifier to apply to the container
 */
@Composable
fun CardLoader(
    isLoading: Boolean,
    skeleton: @Composable () -> Unit,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isLoading) {
        skeleton()
    } else {
        content()
    }
}
