package app.readylytics.health.util

import app.readylytics.health.data.security.SecureFileStore
import app.readylytics.health.data.security.secureFileAssociatedData
import java.io.File

/**
 * The bounded, rotating, encrypted slot set behind [SecureFileLogSink].
 *
 * Slots are `prod_logs.txt` (active, newest) plus `prod_logs.txt.1` .. `prod_logs.txt.N`
 * (N = [maxBackups], `.N` oldest). The active slot's plaintext is mirrored in [activeBuffer], so a
 * flush encrypts exactly one slot instead of decrypting and rewriting all of them (perf item F2).
 * Holding it in memory is bounded by [maxFileSize] and is the whole point: without it, appending to
 * a streaming-AEAD file is impossible without re-encrypting what is already there.
 *
 * Every slot is encrypted under the single constant [LOG_ASSOCIATED_DATA] rather than
 * [secureFileAssociatedData]'s per-filename default. That is what makes rotation a rename instead
 * of a re-encryption. Files written before F2 are bound to their own filename, so
 * [normalizeLegacySlots] rewrites them under the constant AD exactly once, before any rename can
 * make them permanently undecryptable (a failed AEAD check surfaces as an empty string, i.e. silent
 * log loss).
 *
 * Not thread-safe by design: [SecureFileLogSink] only ever calls it from its single-threaded write
 * dispatcher (`Dispatchers.IO.limitedParallelism(1)`), so no additional locking is needed.
 */
internal class LogSlotStore(
    private val logDirectory: File,
    private val maxFileSize: Long,
    private val maxBackups: Int,
    private val encryptStreams: Boolean,
    private val secureFileStore: SecureFileStore,
) {
    private val activeFile = File(logDirectory, ACTIVE_FILE_NAME)
    private val migrationMarker = File(logDirectory, MIGRATION_MARKER_NAME)

    private val activeBuffer = StringBuilder()
    private var activeBytes = 0
    private var hydrated = false

    /** Appends already-formatted log lines (each terminated with `\n`) to the active slot. */
    fun appendLines(lines: List<String>) {
        if (lines.isEmpty()) return
        hydrate()
        for (line in lines) {
            val lineBytes = line.byteSize()
            if (activeBytes > 0 && activeBytes + lineBytes > maxFileSize) {
                sealActiveSlot()
            }
            activeBuffer.append(line)
            activeBytes += lineBytes
        }
        writeActiveSlot()
    }

    /** Backups oldest-first, then the active slot — the same chronological order as before F2. */
    fun readAll(): String {
        hydrate()
        val builder = StringBuilder()
        for (index in maxBackups downTo 1) {
            builder.append(readSlot(backupFile(index)))
        }
        builder.append(activeBuffer)
        return builder.toString()
    }

    private fun hydrate() {
        if (hydrated) return
        hydrated = true
        if (!logDirectory.exists()) logDirectory.mkdirs()
        normalizeLegacySlots()
        val existing = readSlot(activeFile)
        activeBuffer.append(existing)
        activeBytes = existing.byteSize()
    }

    /**
     * One-time upgrade path. Pre-F2 slots are AEAD-bound to their own filename; re-encrypt each one
     * in place under [LOG_ASSOCIATED_DATA] so later renames stay decryptable.
     *
     * Deliberately rewrites in place rather than re-slicing into the new [maxFileSize] geometry: a
     * crash mid-normalization can then only duplicate a slot's content, never lose it. The cost is
     * that migrated slots keep their original (larger) size until they rotate out, so total on-disk
     * retention can briefly exceed `(maxBackups + 1) * maxFileSize`. That is transient and bounded
     * by the old configuration.
     */
    private fun normalizeLegacySlots() {
        if (migrationMarker.exists()) return
        if (!encryptStreams) {
            markNormalized()
            return
        }
        val legacyContents =
            orderedSlotFiles().map { file ->
                file to readSlotWithLegacyAssociatedData(file)
            }
        for ((file, content) in legacyContents) {
            if (content.isNotEmpty()) {
                writeSlot(file, content)
            }
        }
        markNormalized()
    }

    private fun markNormalized() {
        runCatching { migrationMarker.writeText(MIGRATION_MARKER_CONTENT) }
    }

    private fun sealActiveSlot() {
        writeActiveSlot()
        rotate()
        activeBuffer.setLength(0)
        activeBytes = 0
    }

    private fun rotate() {
        if (maxBackups <= 0) {
            activeFile.delete()
            return
        }
        backupFile(maxBackups).delete()
        for (index in maxBackups - 1 downTo 1) {
            val source = backupFile(index)
            if (source.exists()) source.renameTo(backupFile(index + 1))
        }
        if (activeFile.exists()) activeFile.renameTo(backupFile(1))
    }

    private fun writeActiveSlot() = writeSlot(activeFile, activeBuffer.toString())

    /** Oldest first: `.maxBackups` … `.1`, then the active slot. */
    private fun orderedSlotFiles(): List<File> =
        buildList {
            for (index in maxBackups downTo 1) add(backupFile(index))
            add(activeFile)
        }

    private fun backupFile(index: Int): File = File(logDirectory, "$ACTIVE_FILE_NAME.$index")

    private fun readSlot(file: File): String = readSlot(file, LOG_ASSOCIATED_DATA)

    private fun readSlotWithLegacyAssociatedData(file: File): String = readSlot(file, secureFileAssociatedData(file))

    private fun readSlot(
        file: File,
        associatedData: ByteArray,
    ): String {
        if (!file.exists()) return ""
        return try {
            if (encryptStreams) {
                secureFileStore.readText(file, associatedData)
            } else {
                file.readText()
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun writeSlot(
        file: File,
        content: String,
    ) {
        if (content.isEmpty()) {
            file.delete()
            return
        }
        if (encryptStreams) {
            secureFileStore.writeText(file, content, LOG_ASSOCIATED_DATA)
        } else {
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
    }

    private fun String.byteSize(): Int = toByteArray(Charsets.UTF_8).size

    companion object {
        const val ACTIVE_FILE_NAME = "prod_logs.txt"
        private const val MIGRATION_MARKER_NAME = ".slot_ad_v2"
        private const val MIGRATION_MARKER_CONTENT = "1"

        /**
         * Constant associated data for every log slot. Must never change: altering it makes every
         * previously written slot fail its AEAD check and read back as empty.
         */
        private val LOG_ASSOCIATED_DATA: ByteArray = "readylytics_prod_logs".toByteArray(Charsets.UTF_8)
    }
}
