package app.readylytics.health.core.model.domain.util

import org.junit.Test
import java.io.IOException
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeDiagnosticFormatterTest {
    @Test
    fun `release diagnostics omit nested payloads`() {
        val corpus =
            listOf(
                "bpm=187",
                "rmssd=94.7",
                "52.5200,13.4050",
                "source_private_123",
                "content://private/tree/secret",
                "backup-secret",
            )
        val root = IllegalStateException(corpus.joinToString(";"), IllegalArgumentException(corpus[3]))
        root.addSuppressed(IOException(corpus[4]))
        val text = safeDiagnostic(DiagnosticReason.RESTORE_FAILED, root)
        corpus.forEach { assertFalse(text.contains(it)) }
        assertTrue(text.contains("RESTORE_FAILED"))
        assertTrue(text.contains("java.lang.IllegalStateException"))
        assertTrue(text.contains("SafeDiagnosticFormatterTest"))
    }

    @Test
    fun `cyclic causes do not loop infinitely or oom`() {
        val t1 = IllegalStateException("cycle1")
        val t2 = IllegalArgumentException("cycle2", t1)
        t1.initCause(t2)
        val text = safeDiagnostic(DiagnosticReason.OPERATION_FAILED, t1)
        assertTrue(text.contains("OPERATION_FAILED"))
        assertTrue(text.contains("IllegalStateException"))
        assertTrue(text.contains("IllegalArgumentException"))
    }

    @Test
    fun `suppressed cycles terminate cleanly`() {
        val root = IllegalStateException("root")
        val suppressed = IllegalArgumentException("suppressed")
        root.addSuppressed(suppressed)
        suppressed.addSuppressed(root)
        val text = safeDiagnostic(DiagnosticReason.OPERATION_FAILED, root)
        assertTrue(text.contains("OPERATION_FAILED"))
        assertTrue(text.contains("IllegalStateException"))
        assertTrue(text.contains("IllegalArgumentException"))
    }

    @Test
    fun `very deep exception chains are bounded`() {
        var current: Throwable = IllegalStateException("depth-0")
        for (i in 1..40) {
            current = RuntimeException("depth-$i", current)
        }
        val text = safeDiagnostic(DiagnosticReason.OPERATION_FAILED, current)
        assertTrue(text.contains("OPERATION_FAILED"))
        val classLines = text.lines().filter { it.startsWith("java.lang.") }
        assertTrue(classLines.size <= 16)
    }

    @Test
    fun `null failure outputs reason name`() {
        val text = safeDiagnostic(DiagnosticReason.OPERATION_FAILED, null)
        assertTrue(text.startsWith("OPERATION_FAILED"))
    }
}
