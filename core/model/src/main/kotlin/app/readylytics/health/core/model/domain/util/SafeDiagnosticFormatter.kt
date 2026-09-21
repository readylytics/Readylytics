package app.readylytics.health.core.model.domain.util

import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

enum class DiagnosticReason {
    OPERATION_FAILED,
    PERMISSION_DENIED,
    BACKUP_FAILED,
    RESTORE_FAILED,
    LOG_WRITE_FAILED,
}

object SafeDiagnosticFormatter {
    private const val MAX_CAUSE_CHAIN_DEPTH = 16
    private const val MAX_FRAMES_PER_THROWABLE = 32
    private const val MAX_SUPPRESSED_EXCEPTIONS = 8

    fun safeDiagnostic(
        reason: DiagnosticReason,
        failure: Throwable?,
    ): String =
        buildString {
            appendLine(reason.name)
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            val pending = ArrayDeque<Throwable>()
            failure?.let(pending::add)
            var remaining = MAX_CAUSE_CHAIN_DEPTH
            while (pending.isNotEmpty() && remaining-- > 0) {
                val next = pending.removeFirst()
                if (!seen.add(next)) continue
                appendLine(next.javaClass.name)
                next.stackTrace.take(MAX_FRAMES_PER_THROWABLE).forEach { frame ->
                    appendLine("at ${frame.className}.${frame.methodName}:${frame.lineNumber}")
                }
                next.cause?.let(pending::add)
                next.suppressed.take(MAX_SUPPRESSED_EXCEPTIONS).forEach(pending::add)
            }
        }
}

fun safeDiagnostic(
    reason: DiagnosticReason,
    failure: Throwable?,
): String = SafeDiagnosticFormatter.safeDiagnostic(reason, failure)

