package app.readylytics.health.benchmark

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * Only compiled into performance build types. Compose only publishes
 * `Modifier.testTag` values into the accessibility tree (as
 * `viewIdResourceName`) when the composition opts in via
 * `testTagsAsResourceId = true` on some ancestor semantics node. Without
 * this, UiAutomator's `By.res(...)` selectors in ScrollBenchmark (see the
 * :benchmark module) can never find tagged Compose nodes such as
 * "HrvTrendChart". Applied once at MainActivity's composition root.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.applyBenchmarkTestTagSemantics(): Modifier = this.semantics { testTagsAsResourceId = true }
