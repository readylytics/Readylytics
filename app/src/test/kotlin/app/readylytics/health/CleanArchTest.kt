package app.readylytics.health

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.imports
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.Test

class CleanArchTest {
    @Test
    fun `ui package does not import room daos`() {
        Konsist
            .scopeFromProject()
            .files
            .filter { it.hasPackage("app.readylytics.health.ui..") }
            .assertTrue { file ->
                val hasDaoImport =
                    file.imports.any { import ->
                        import.name.startsWith("app.readylytics.health.data.local.dao")
                    }
                !hasDaoImport
            }
    }
}
