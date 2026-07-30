package app.readylytics.health.benchmark

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.applyBenchmarkTestTagSemantics(): Modifier =
    this.semantics {
        testTagsAsResourceId = true
    }
