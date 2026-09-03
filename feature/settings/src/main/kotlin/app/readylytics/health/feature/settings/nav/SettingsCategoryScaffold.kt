package app.readylytics.health.feature.settings.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

fun resolveHighlightIndex(
    itemIds: List<String>,
    highlightItemId: String?,
): Int {
    if (highlightItemId == null) return -1
    return itemIds.indexOf(highlightItemId)
}

private const val HIGHLIGHT_PULSE_DURATION_MS = 1_200L
private const val HIGHLIGHT_FADE_MS = 400

data class SettingsCategoryListItem(
    val id: String,
    val content: @Composable () -> Unit,
)

@Composable
fun SettingsCategoryScaffold(
    items: List<SettingsCategoryListItem>,
    highlightItemId: String?,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var pulsingId by remember(highlightItemId) { mutableStateOf(highlightItemId) }

    LaunchedEffect(highlightItemId) {
        val targetIndex = resolveHighlightIndex(items.map { it.id }, highlightItemId)
        if (targetIndex >= 0) {
            listState.animateScrollToItem(targetIndex)
        }
    }

    LaunchedEffect(pulsingId) {
        if (pulsingId != null) {
            delay(HIGHLIGHT_PULSE_DURATION_MS)
            pulsingId = null
        }
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        items(items = items, key = { it.id }) { item ->
            val backgroundColor by animateColorAsState(
                targetValue =
                    if (item.id == pulsingId) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    },
                animationSpec = tween(durationMillis = HIGHLIGHT_FADE_MS),
                label = "settingsItemHighlight",
            )
            Box(modifier = Modifier.fillMaxWidth().background(backgroundColor)) {
                item.content()
            }
        }
    }
}
