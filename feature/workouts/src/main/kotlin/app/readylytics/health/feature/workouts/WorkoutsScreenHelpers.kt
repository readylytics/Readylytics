package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.components.SectionHeader

/**
 * Simple display helper for rendering section headers in workouts screens.
 */
@Composable
fun ChartsSectionHeader(title: String) {
    SectionHeader(
        title = title,
    )
}

/**
 * Wrapper for conditional content display based on loading state.
 */
@Composable
fun ContentLoadingWrapper(
    isLoading: Boolean,
    content: @Composable () -> Unit,
) {
    if (!isLoading) {
        content()
    }
}

/**
 * Spacer component with standard spacing between sections.
 */
@Composable
fun SpacerBetweenSections() {
    Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))
}
