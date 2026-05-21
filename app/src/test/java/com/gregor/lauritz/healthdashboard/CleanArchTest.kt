package com.gregor.lauritz.healthdashboard

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.imports
import com.lemonappdev.konsist.api.verify.assert
import org.junit.Test

class CleanArchTest {
    @Test
    fun `ui package does not import room daos`() {
        Konsist
            .scopeFromProject()
            .files
            .filter { it.hasPackage("com.gregor.lauritz.healthdashboard.ui..") }
            .assert { file ->
                val hasDaoImport =
                    file.imports.any { import ->
                        import.name.startsWith("com.gregor.lauritz.healthdashboard.data.local.dao")
                    }
                !hasDaoImport
            }
    }
}
