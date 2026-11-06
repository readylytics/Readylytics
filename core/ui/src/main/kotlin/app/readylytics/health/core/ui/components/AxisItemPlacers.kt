package app.readylytics.health.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis

@Composable
fun rememberCountItemPlacer(count: Int = 5): VerticalAxis.ItemPlacer =
    remember(count) { VerticalAxis.ItemPlacer.count(count = { count }) }
