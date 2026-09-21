package app.readylytics.health.data.backup

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import java.io.BufferedWriter

/**
 * Streams one backup table as a JSON array, pulling rows through [page] until a page comes back
 * empty and calling [advance] with every row so the caller can carry its keyset cursor forward.
 * Shared by [BackupStreamWriter] and [CoverageBackupWriter] so both use the identical paging and
 * cancellation behaviour.
 */
internal suspend inline fun <reified T> BufferedWriter.writeBackupTable(
    json: Json,
    name: String,
    page: () -> List<T>,
    advance: (T) -> Unit,
    noinline pageHook: (suspend (tableName: String) -> Unit)? = null,
) {
    write("  \"$name\": [\n")
    var first = true
    while (true) {
        currentCoroutineContext().ensureActive()
        val chunk = page()
        if (chunk.isEmpty()) break
        for (item in chunk) {
            if (!first) write(",\n")
            write("    ${json.encodeToString(item)}")
            first = false
            advance(item)
        }
        pageHook?.invoke(name)
    }
    write("\n  ]")
}
