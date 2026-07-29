package app.readylytics.health.benchmark

import androidx.compose.ui.Modifier

/**
 * No-op for this build type. app/src/benchmark provides the real
 * implementation (same package + function name) that opts the composition
 * into publishing `Modifier.testTag` values into the accessibility tree
 * (`viewIdResourceName`), which is what lets UiAutomator's `By.res(...)`
 * find Compose nodes during Macrobenchmark journeys — see ScrollBenchmark in
 * the :benchmark module. This exact file also exists in the sibling
 * debug/release build type — main has no copy at all, so exactly one of
 * {debug, release, benchmark} is on the compile path per variant and there
 * is no redeclaration conflict.
 */
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.applyBenchmarkTestTagSemantics(): Modifier = this.semantics {
    testTagsAsResourceId = true
}
