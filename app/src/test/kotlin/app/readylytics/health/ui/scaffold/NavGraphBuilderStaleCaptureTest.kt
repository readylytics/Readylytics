package app.readylytics.health.ui.scaffold

import com.lemonappdev.konsist.api.Konsist
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `NavHost` remembers its `builder` lambda ("the contents of the builder cannot be changed"), so
 * every `NavGraphBuilder` extension that declares the graph runs exactly once. Any plain value
 * passed into one is therefore frozen at graph-creation time and can never reflect later state
 * changes -- which silently broke "Continue in background" on the resync progress screen.
 *
 * Pass a `State<T>`, a lambda, or a stable holder instead.
 */
class NavGraphBuilderStaleCaptureTest {
    @Test
    fun `nav graph builder extensions never take snapshot-frozen value parameters`() {
        val frozenTypes = setOf("Boolean", "Int", "Long", "Float", "Double", "String")

        val offenders =
            Konsist
                .scopeFromProject()
                .functions(includeNested = true)
                .filter { it.receiverType?.name == "NavGraphBuilder" }
                .flatMap { function ->
                    function.parameters
                        .filter { it.type.name in frozenTypes }
                        .map { "${function.name}(${it.name}: ${it.type.name})" }
                }

        assertTrue(
            "NavGraphBuilder extensions must not take plain value parameters (they are captured " +
                "once by the remembered NavHost builder and go stale): $offenders",
            offenders.isEmpty(),
        )
    }
}
