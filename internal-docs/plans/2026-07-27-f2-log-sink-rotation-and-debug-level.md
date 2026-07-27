# F2: SecureFileLogSink rotation + DEBUG log level — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the release log sink from decrypting and rewriting every log slot on every flush,
and add a DEBUG level that keeps sync chatter out of the encrypted diagnostic file.

**Architecture:** A new `LogSlotStore` owns the encrypted slot set (`prod_logs.txt` +
`prod_logs.txt.1..N`), mirrors the active slot's plaintext in memory, and rotates by rename. All
slots are encrypted under one constant associated data instead of the per-filename default that
`SecureFileStore` applies by default — that is what makes rename-rotation legal. Files written by
the current implementation are AEAD-bound to their own filename, so a one-time in-place
re-encryption runs before any rename can make them permanently undecryptable. `SecureFileLogSink`
keeps only formatting, sanitizing, and flush scheduling. Separately, `LogLevel` gains `DEBUG`,
`logD` maps to it, and the release sink declares DEBUG not loggable.

**Tech Stack:** Kotlin, Tink StreamingAEAD (via `SecureFileStore`), kotlinx-coroutines
(`Dispatchers.IO.limitedParallelism(1)`), JUnit4 + MockK + `TemporaryFolder`.

## Global Constraints

- Pre-commit, mandatory, every commit: `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`.
  Run `./gradlew lintRelease` once after the final commit of this plan.
- File size: target ≤400 lines, hard limit ≤800.
- New files → run `codegraph index` after the task that creates them.
- `internal-docs/DATA_FLOW.md` is NOT affected by this plan (logging is not ingestion, Room schema,
  or scoring). Do not edit it.
- No user-facing strings are added, so `strings.xml` is untouched.
- Load-bearing intent comments are house style: every caching/buffering decision gets a short
  comment saying why it is safe.
- Retained log capacity stays at ~6 MB total, unchanged from today.
- Chronological ordering of `readLogsDecrypted()` output must be preserved exactly: backups
  oldest-first, then the active slot.
- Behavior that must NOT regress: `LogcatCaptureStoreImpl.kt:34` is the only production consumer of
  `readLogsDecrypted()`; its output feeds the user-facing diagnostics export.

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/kotlin/app/readylytics/health/util/LogSlotStore.kt` (**create**) | The bounded rotating encrypted slot set. Owns the active-slot memory buffer, rename-rotation, constant-AD crypto, and the one-time legacy normalization. ~180 lines. |
| `app/src/main/kotlin/app/readylytics/health/util/SecureFileLogSink.kt` (**modify**) | Formatting, sanitizing, buffering, flush scheduling, logcat mirroring, `isLoggable`. Shrinks from 329 to ~190 lines as the slot machinery moves out. |
| `app/src/test/kotlin/app/readylytics/health/util/LogSlotStoreTest.kt` (**create**) | Rotation boundary, structural capacity bound, ordering, hydrate-then-append, legacy-AD normalization, crash window. |
| `app/src/test/kotlin/app/readylytics/health/util/SecureFileLogSinkTest.kt` (**modify**) | Existing sink-level tests; two assertions change meaning (see Task 2 Step 1), thresholds become injectable, DEBUG filtering test added in Task 3. |
| `core/model/src/main/kotlin/app/readylytics/health/domain/util/AppLog.kt` (**modify**) | `LogLevel.DEBUG`, `logD` → DEBUG, new `logI` → INFO. |
| `core/model/src/test/kotlin/app/readylytics/health/domain/util/AppLogTest.kt` (**modify**) | Level-mapping tests for `logD`/`logI`. |
| `app/src/main/kotlin/app/readylytics/health/HealthDashboardApplication.kt` (**modify**) | Debug-build sink gains a DEBUG branch; its INFO branch moves from `Log.d` to `Log.i`. |
| `core/healthconnect/.../ForegroundSyncController.kt`, `.../DailySyncUseCase.kt`, `.../ResyncRangeUseCase.kt`, `.../HealthConnectRepositoryImpl.kt` (**modify**) | Twelve call sites promoted from `logD` to `logI` so release diagnostics keep sync narration. |
| `internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md` (**modify**) | Mark F2 implemented in Task 4. |

## Why the current code is slow (context for the implementer)

`SecureFileLogSink.persistLogs()` (`:122-131`) currently runs on every flush — which fires at 5
buffered lines or every 2 s:

1. `readAllLogs()` Tink-decrypts **all** slots (2 MB × 3 today),
2. concatenates them into one `String`,
3. `retainWithinTotalCapacity()` does a full `toByteArray()` pass to measure,
4. `partitionIntoSlots()` re-splits the whole thing,
5. `writeChunks()` deletes and re-encrypts **every** slot.

So a chatty sync drives multi-MB crypto + string churn every couple of seconds, concurrent with the
UI. After this plan, a flush encrypts one ≤512 KB slot and touches nothing else.

## The associated-data trap (read before Task 1)

`app/src/main/kotlin/app/readylytics/health/data/security/SecureFileStore.kt:20`:

```kotlin
fun secureFileAssociatedData(file: File): ByteArray = file.name.toByteArray(Charsets.UTF_8)
```

`readText`/`writeText` default `associatedData` to that. So a file encrypted as `prod_logs.txt` and
then renamed to `prod_logs.txt.1` **fails its AEAD check on read** — and
`TinkSecureFileStore.readText` catches the exception and returns `""` (`SecureFileStore.kt:51-54`).
The failure is therefore *silent log loss*, not a crash. Both parameters accept an explicit
`associatedData`, so this plan passes a constant for all log slots and normalizes pre-existing files
once.

---

### Task 1: `LogSlotStore` — rotating slot set with constant-AD crypto

**Files:**
- Create: `app/src/main/kotlin/app/readylytics/health/util/LogSlotStore.kt`
- Test: `app/src/test/kotlin/app/readylytics/health/util/LogSlotStoreTest.kt`

**Interfaces:**
- Consumes: `app.readylytics.health.data.security.SecureFileStore` (`readText(file, associatedData)`,
  `writeText(file, content, associatedData)`) and
  `app.readylytics.health.data.security.secureFileAssociatedData(file)`.
- Produces, relied on by Task 2:
  - `internal class LogSlotStore(logDirectory: File, maxFileSize: Long, maxBackups: Int, encryptStreams: Boolean, secureFileStore: SecureFileStore)`
  - `fun appendLines(lines: List<String>)` — each element must already end with `\n`.
  - `fun readAll(): String` — backups oldest-first, then the active slot.
  - `companion object { const val ACTIVE_FILE_NAME = "prod_logs.txt" }`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/app/readylytics/health/util/LogSlotStoreTest.kt`:

```kotlin
package app.readylytics.health.util

import app.readylytics.health.data.security.SecureFileStore
import app.readylytics.health.data.security.secureFileAssociatedData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogSlotStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun store(
        directory: File,
        maxFileSize: Long = 100L,
        maxBackups: Int = 2,
        encryptStreams: Boolean = true,
        secureFileStore: SecureFileStore,
    ) = LogSlotStore(
        logDirectory = directory,
        maxFileSize = maxFileSize,
        maxBackups = maxBackups,
        encryptStreams = encryptStreams,
        secureFileStore = secureFileStore,
    )

    private fun line(text: String) = "$text\n"

    @Test
    fun appendWritesOnlyTheActiveSlot() {
        val directory = tempFolder.newFolder("logs")
        val fake = AdAwareSecureFileStore()
        val subject = store(directory, secureFileStore = fake)

        subject.appendLines(listOf(line("a"), line("b")))

        assertEquals(listOf("prod_logs.txt"), fake.writtenNames.distinct())
        assertEquals("a\nb\n", subject.readAll())
    }

    @Test
    fun rotationSealsAtLineBoundariesAndNeverSplitsALine() {
        val directory = tempFolder.newFolder("logs")
        val fake = AdAwareSecureFileStore()
        // Slot holds 10 bytes; each line is 6 bytes, so every second line forces a seal.
        val subject = store(directory, maxFileSize = 10L, secureFileStore = fake)

        subject.appendLines((1..6).map { line("line$it") })

        val slots = fake.storedPlaintextByName()
        assertTrue("No slot may contain a partial line", slots.values.all { it.endsWith("\n") })
        assertTrue("Slot count is bounded by maxBackups + 1", slots.size <= 3)
        // Six 6-byte lines into a 10-byte slot: each line seals the previous one, so the two
        // backups hold line4/line5 and the active slot holds line6. line1..line3 have aged out.
        assertEquals("line4\nline5\nline6\n", subject.readAll())
    }

    @Test
    fun readAllReturnsBackupsOldestFirstThenActive() {
        val directory = tempFolder.newFolder("logs")
        val fake = AdAwareSecureFileStore()
        val subject = store(directory, maxFileSize = 10L, secureFileStore = fake)

        subject.appendLines(listOf(line("aaaaa")))
        subject.appendLines(listOf(line("bbbbb")))
        subject.appendLines(listOf(line("ccccc")))

        assertEquals("aaaaa\nbbbbb\nccccc\n", subject.readAll())
    }

    @Test
    fun oldestSlotIsDroppedOnceBackupsAreFull() {
        val directory = tempFolder.newFolder("logs")
        val fake = AdAwareSecureFileStore()
        val subject = store(directory, maxFileSize = 10L, maxBackups = 1, secureFileStore = fake)

        subject.appendLines(listOf(line("aaaaa")))
        subject.appendLines(listOf(line("bbbbb")))
        subject.appendLines(listOf(line("ccccc")))

        assertEquals("Oldest slot must age out", "bbbbb\nccccc\n", subject.readAll())
    }

    @Test
    fun aSecondInstanceHydratesFromDiskAndAppends() {
        val directory = tempFolder.newFolder("logs")
        val fake = AdAwareSecureFileStore()
        store(directory, secureFileStore = fake).appendLines(listOf(line("first")))

        val reopened = store(directory, secureFileStore = fake)
        reopened.appendLines(listOf(line("second")))

        assertEquals("first\nsecond\n", reopened.readAll())
    }

    @Test
    fun legacyFilenameBoundSlotsAreReadableAndSurviveALaterRotation() {
        val directory = tempFolder.newFolder("logs")
        val fake = AdAwareSecureFileStore()
        // Simulate the pre-F2 on-disk format: each slot bound to its own filename.
        fake.writeText(File(directory, "prod_logs.txt.1"), "old-backup\n", legacyAd("prod_logs.txt.1"))
        fake.writeText(File(directory, "prod_logs.txt"), "old-active\n", legacyAd("prod_logs.txt"))

        val subject = store(directory, maxFileSize = 10L, secureFileStore = fake)
        assertEquals("old-backup\nold-active\n", subject.readAll())

        // Force enough rotations that the migrated slots are renamed at least once.
        subject.appendLines(listOf(line("new1")))
        subject.appendLines(listOf(line("new2")))

        assertTrue(
            "Migrated content must stay decryptable after a rename",
            subject.readAll().contains("old-active"),
        )
        assertFalse(
            "Nothing may still be bound to a filename",
            fake.readsThatFailedAuthentication.isNotEmpty(),
        )
    }

    @Test
    fun plaintextModeSkipsTheSecureFileStoreEntirely() {
        val directory = tempFolder.newFolder("logs")
        val fake = AdAwareSecureFileStore()
        val subject = store(directory, encryptStreams = false, secureFileStore = fake)

        subject.appendLines(listOf(line("plain")))

        assertTrue(fake.writtenNames.isEmpty())
        assertEquals("plain\n", File(directory, "prod_logs.txt").readText())
        assertEquals("plain\n", subject.readAll())
    }

    private fun legacyAd(name: String): ByteArray = name.toByteArray(Charsets.UTF_8)

    /**
     * Models Tink's AEAD binding: content written under one associatedData cannot be read back
     * under a different one. The production store swallows that failure and returns "" — this fake
     * does the same, and records it so a test can assert it never happened.
     *
     * Ciphertext is keyed by filename, so a rename by [LogSlotStore] would orphan the entry. Each
     * on-disk placeholder therefore carries the name it was written under; [followRenames] re-keys
     * the map when it sees the file has moved. That mirrors reality: a rename moves the bytes and
     * leaves the AD binding alone.
     */
    private class AdAwareSecureFileStore : SecureFileStore {
        private data class Entry(val content: String, val associatedData: String)

        private val entries = linkedMapOf<String, Entry>()
        val writtenNames = mutableListOf<String>()
        val readsThatFailedAuthentication = mutableListOf<String>()

        override fun readText(
            file: File,
            associatedData: ByteArray,
        ): String {
            followRenames(file)
            val entry = entries[file.name] ?: return ""
            if (entry.associatedData != associatedData.toString(Charsets.UTF_8)) {
                readsThatFailedAuthentication += file.name
                return ""
            }
            return entry.content
        }

        override fun writeText(
            file: File,
            content: String,
            associatedData: ByteArray,
        ) {
            writtenNames += file.name
            entries[file.name] = Entry(content, associatedData.toString(Charsets.UTF_8))
            file.parentFile?.mkdirs()
            file.writeText(CIPHERTEXT_PREFIX + file.name)
        }

        fun storedPlaintextByName(): Map<String, String> = entries.mapValues { it.value.content }

        private fun followRenames(file: File) {
            if (entries.containsKey(file.name) || !file.exists()) return
            val writtenUnder = file.readText().removePrefix(CIPHERTEXT_PREFIX)
            if (writtenUnder != file.name) {
                entries.remove(writtenUnder)?.let { entries[file.name] = it }
            }
        }

        private companion object {
            const val CIPHERTEXT_PREFIX = "ciphertext:"
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "app.readylytics.health.util.LogSlotStoreTest"`
Expected: FAIL — compilation error, `Unresolved reference: LogSlotStore`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/app/readylytics/health/util/LogSlotStore.kt`:

```kotlin
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

    private fun readSlotWithLegacyAssociatedData(file: File): String =
        readSlot(file, secureFileAssociatedData(file))

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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "app.readylytics.health.util.LogSlotStoreTest"`
Expected: PASS, 7 tests.

If `rotationSealsAtLineBoundariesAndNeverSplitsALine` fails on slot count, check that `rotate()`
deletes `backupFile(maxBackups)` *before* shifting — otherwise a stale `.3` survives.

- [ ] **Step 5: Format, full test run, index, commit**

```bash
./gradlew ktlintFormat && ./gradlew testDebugUnitTest
codegraph index
git add app/src/main/kotlin/app/readylytics/health/util/LogSlotStore.kt \
        app/src/test/kotlin/app/readylytics/health/util/LogSlotStoreTest.kt
git commit -m "perf: add LogSlotStore with rename-rotation and constant-AD log slots (F2)"
```

`LogSlotStore` is unused until Task 2 — that is intentional, it keeps the rotation logic reviewable
on its own.

---

### Task 2: Wire `SecureFileLogSink` onto `LogSlotStore` and raise the flush thresholds

**Files:**
- Modify: `app/src/main/kotlin/app/readylytics/health/util/SecureFileLogSink.kt`
- Modify: `app/src/test/kotlin/app/readylytics/health/util/SecureFileLogSinkTest.kt`

**Interfaces:**
- Consumes: `LogSlotStore(logDirectory, maxFileSize, maxBackups, encryptStreams, secureFileStore)`,
  `appendLines(List<String>)`, `readAll()`, `LogSlotStore.ACTIVE_FILE_NAME` (Task 1).
- Produces, relied on by Task 3: `SecureFileLogSink`'s constructor gains
  `flushLineThreshold: Int = DEFAULT_FLUSH_LINE_THRESHOLD` and
  `flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS`; `DEFAULT_MAX_FILE_SIZE_BYTES` becomes
  `512L * 1024L` and `DEFAULT_MAX_BACKUPS` becomes `11`.

- [ ] **Step 1: Update the two existing tests whose invariants change, and add a threshold test**

Two current assertions encode the old *byte-exact trimming* behavior, which mid-line-truncated log
entries via `takeLastUtf8Bytes`. The new design never emits a partial line, so a slot may exceed
`maxFileSize` when a single line is larger than a slot. Update them:

In `testFileRotationCreatesRotatedBackups`, replace the per-slot size assertion:

```kotlin
            assertTrue("Rotation should never exceed active plus backup slots", storedContents.size <= 3)
            assertTrue(
                "Lines are never split: every slot ends on a line boundary",
                storedContents.values.all { it.endsWith("\n") },
            )
            // A single line larger than a slot occupies its own slot, so the bound is per-line,
            // not per-byte. Total retention is structural: (maxBackups + 1) slots.
            assertTrue("Total retention must stay bounded", totalBytes <= 3 * (40L + longestLineBytes(storedContents)))
```

and add to the test class:

```kotlin
    private fun longestLineBytes(storedContents: Map<String, String>): Long =
        storedContents.values
            .flatMap { it.split("\n") }
            .maxOfOrNull { it.toByteArray(Charsets.UTF_8).size.toLong() + 1L } ?: 0L
```

In `testTotalRetentionStaysWithinConfiguredBound`, replace `assertTrue(totalBytes <= 160L)` with:

```kotlin
            assertTrue("Slot count is structurally bounded", secureFileStore.storedContents().size <= 2)
            assertTrue(
                "Total retention must stay bounded",
                totalBytes <= 2 * (80L + longestLineBytes(secureFileStore.storedContents())),
            )
```

Rename and retarget the two threshold tests. `testBufferingBeforeFiveLogs` → keep the name but pass
`flushLineThreshold = 5` explicitly so it still describes real behavior; `testFlushOnFiveLogs` →
same. `testFlushAfterTwoSeconds` becomes:

```kotlin
    @Test
    fun testFlushAfterInterval() =
        runBlocking {
            val sink =
                SecureFileLogSink(
                    context = mockContext,
                    maxFileSize = 10000L,
                    maxBackups = 2,
                    encryptStreams = false,
                    coroutineContext = Dispatchers.IO,
                    flushLineThreshold = 64,
                    flushIntervalMs = 300L,
                )

            sink.log(LogLevel.INFO, "TestTag", "Timed Log", null, LogContext("session-1"))

            val logFile = File(cacheDir, "logs/prod_logs.txt")
            delay(50)
            assertTrue(!logFile.exists() || logFile.readText().isEmpty())

            delay(500)
            assertTrue(logFile.exists())
            assertTrue(logFile.readText().contains("Timed Log"))
        }
```

Add a new test proving the flush no longer rewrites backups:

```kotlin
    @Test
    fun testFlushWritesOnlyTheActiveSlot() =
        runBlocking {
            val secureFileStore = FakeSecureFileStore()
            val sink =
                SecureFileLogSink(
                    context = mockContext,
                    maxFileSize = 10_000L,
                    maxBackups = 2,
                    encryptStreams = true,
                    coroutineContext = Dispatchers.Unconfined,
                    secureFileStore = secureFileStore,
                    flushLineThreshold = 2,
                )

            for (i in 1..6) {
                sink.log(LogLevel.INFO, "TestTag", "Log $i", null, LogContext("session-1"))
            }

            assertEquals(
                "A flush must never touch backup slots",
                listOf("prod_logs.txt"),
                secureFileStore.writeCalls.distinct(),
            )
        }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "app.readylytics.health.util.SecureFileLogSinkTest"`
Expected: FAIL — compilation error, `No parameter with name 'flushLineThreshold' found`.

- [ ] **Step 3: Rewrite `SecureFileLogSink` around `LogSlotStore`**

Replace the whole file body between the imports and the companion object. Delete these members
entirely: `persistLogs`, `readAllLogs`, `orderedLogFiles`, `readFileContent`, `writeChunks`,
`writeFileContent`, `retainWithinTotalCapacity`, `trimSegmentsToCapacity`, `partitionIntoSlots`,
`String.lineSegments`, `String.byteSize`, `String.takeLastUtf8Bytes`, and the `logFile` property.
Drop the now-unused imports `java.io.FileOutputStream`.

```kotlin
class SecureFileLogSink(
    private val context: Context,
    private val maxFileSize: Long = DEFAULT_MAX_FILE_SIZE_BYTES,
    private val maxBackups: Int = DEFAULT_MAX_BACKUPS,
    private val encryptStreams: Boolean = true,
    coroutineContext: CoroutineContext = Dispatchers.IO.limitedParallelism(1),
    private val secureFileStore: SecureFileStore = TinkSecureFileStore.create(context),
    private val flushLineThreshold: Int = DEFAULT_FLUSH_LINE_THRESHOLD,
    private val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS,
) : DomainLogSink {
    private val writeDispatcher: CoroutineContext = coroutineContext
    private val logDirectory = File(context.cacheDir, "logs")
    private val scope = CoroutineScope(SupervisorJob() + writeDispatcher)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // Slot rotation, crypto, and the active-slot memory buffer live here; this class only formats,
    // sanitizes, and batches. Confined to the single-threaded [writeDispatcher], so LogSlotStore
    // needs no locking of its own.
    private val slotStore =
        LogSlotStore(
            logDirectory = logDirectory,
            maxFileSize = maxFileSize,
            maxBackups = maxBackups,
            encryptStreams = encryptStreams,
            secureFileStore = secureFileStore,
        )

    // Memory buffer for logs
    private val pendingLogs = mutableListOf<String>()
    private var lastWriteTimestamp = System.currentTimeMillis()
    private var flushJob: Job? = null

    init {
        if (!logDirectory.exists()) {
            logDirectory.mkdirs()
        }
    }
```

Keep `log(...)` and `bufferLog(...)` as they are today except for the threshold constants:

```kotlin
        val timeSinceLastWrite = System.currentTimeMillis() - lastWriteTimestamp
        if (pendingLogs.size >= flushLineThreshold || timeSinceLastWrite >= flushIntervalMs) {
            flush(fromSchedule = false)
        } else {
            if (flushJob == null) {
                val delayTime = (flushIntervalMs - timeSinceLastWrite).coerceAtLeast(0)
                flushJob =
                    scope.launch {
                        delay(delayTime.milliseconds)
                        flush(fromSchedule = true)
                    }
            }
        }
```

Replace `flush` and `readLogsDecrypted`:

```kotlin
    private fun flush(fromSchedule: Boolean = false) {
        if (!fromSchedule) {
            flushJob?.cancel()
        }
        flushJob = null

        if (pendingLogs.isEmpty()) return
        val lines = pendingLogs.toList()
        pendingLogs.clear()

        slotStore.appendLines(lines)

        lastWriteTimestamp = System.currentTimeMillis()
    }

    // Safe decryption exposure helper for internal diagnostics use
    suspend fun readLogsDecrypted(): String =
        withContext(writeDispatcher) {
            flush(fromSchedule = false)
            slotStore.readAll()
        }
```

Update the companion constants (keep `sanitizeLogMessage` untouched):

```kotlin
    companion object {
        // 512 KB x 12 slots keeps total retention at the historical ~6 MB while bounding both the
        // in-memory active-slot buffer and the per-flush encrypt to 512 KB (F2). Before F2 a flush
        // decrypted and rewrote all 6 MB.
        const val DEFAULT_MAX_FILE_SIZE_BYTES: Long = 512L * 1024L
        const val DEFAULT_MAX_BACKUPS: Int = 11

        // Raised from 5 lines / 2 s: safe now that a flush costs one small encrypt instead of a
        // full decrypt-rewrite cycle. The durability window (pending lines lost on process death)
        // stays the same order of magnitude.
        const val DEFAULT_FLUSH_LINE_THRESHOLD: Int = 64
        const val DEFAULT_FLUSH_INTERVAL_MS: Long = 5_000L
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "app.readylytics.health.util.SecureFileLogSinkTest"`
Expected: PASS.

`testEncryptedModeDelegatesToSecureFileStore` asserts `writeCalls.last() == "prod_logs.txt"` and
`readCalls.contains("prod_logs.txt")` — both still hold: hydration reads the active slot, appends
write it. `testRotationConcatenationChronological` and `...SomeMissing` use `encryptStreams = false`
with hand-written files and still pass, because `readAll()` reads `.2`, `.1`, then the hydrated
active buffer.

- [ ] **Step 5: Verify the file-size rule and commit**

```bash
wc -l app/src/main/kotlin/app/readylytics/health/util/SecureFileLogSink.kt
```
Expected: well under 400.

```bash
./gradlew ktlintFormat && ./gradlew testDebugUnitTest
git add app/src/main/kotlin/app/readylytics/health/util/SecureFileLogSink.kt \
        app/src/test/kotlin/app/readylytics/health/util/SecureFileLogSinkTest.kt
git commit -m "perf: flush one log slot instead of rewriting all of them (F2)"
```

---

### Task 3: Add the DEBUG level

**Files:**
- Modify: `core/model/src/main/kotlin/app/readylytics/health/domain/util/AppLog.kt:3,66-71`
- Modify: `core/model/src/test/kotlin/app/readylytics/health/domain/util/AppLogTest.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/util/SecureFileLogSink.kt` (the `when (level)`
  at `:59-63` and a new `isLoggable` override)
- Modify: `app/src/main/kotlin/app/readylytics/health/HealthDashboardApplication.kt:160-164`
- Modify: `app/src/test/kotlin/app/readylytics/health/util/SecureFileLogSinkTest.kt`

**Interfaces:**
- Produces, relied on by Task 4: `enum class LogLevel { DEBUG, INFO, WARN, ERROR }`;
  `inline fun logI(tag: String, msg: () -> String)`; `logD` now emits `LogLevel.DEBUG`.

- [ ] **Step 1: Write the failing tests**

Append to `core/model/src/test/kotlin/app/readylytics/health/domain/util/AppLogTest.kt`:

```kotlin
    @Test
    fun testLogDEmitsDebugAndLogIEmitsInfo() {
        val sink = TestLogSink()
        DomainLogger.installSink(sink)

        logD("SyncTest") { "chatty detail" }
        logI("SyncTest") { "lifecycle milestone" }

        assertEquals(2, sink.logs.size)
        assertEquals("[DEBUG] [SyncTest] [Session:none] chatty detail", sink.logs[0])
        assertEquals("[INFO] [SyncTest] [Session:none] lifecycle milestone", sink.logs[1])
    }
```

Append to `app/src/test/kotlin/app/readylytics/health/util/SecureFileLogSinkTest.kt`:

```kotlin
    @Test
    fun testDebugIsNotLoggableAndNeverReachesTheFile() =
        runBlocking {
            val sink =
                SecureFileLogSink(
                    context = mockContext,
                    maxFileSize = 10_000L,
                    maxBackups = 2,
                    encryptStreams = false,
                    coroutineContext = Dispatchers.Unconfined,
                )

            assertFalse("Release sink must drop DEBUG", sink.isLoggable(LogLevel.DEBUG, "TestTag"))
            assertTrue("INFO must still be persisted", sink.isLoggable(LogLevel.INFO, "TestTag"))

            DomainLogger.installSink(sink)
            logD("TestTag") { "chatty sync detail" }
            logI("TestTag") { "sync milestone" }

            val content = sink.readLogsDecrypted()
            assertFalse("DEBUG must not reach the diagnostic file", content.contains("chatty sync detail"))
            assertTrue("INFO must reach the diagnostic file", content.contains("sync milestone"))
        }
```

Add the imports `app.readylytics.health.domain.util.DomainLogger`,
`app.readylytics.health.domain.util.logD`, `app.readylytics.health.domain.util.logI` to that test
file, and add `every { Log.d(any(), any()) } returns 0` to its `setUp()` alongside the existing
`Log.i` mock.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:model:testDebugUnitTest --tests "app.readylytics.health.domain.util.AppLogTest"`
Expected: FAIL — `Unresolved reference: logI`.

- [ ] **Step 3: Implement the level**

In `core/model/src/main/kotlin/app/readylytics/health/domain/util/AppLog.kt`:

```kotlin
enum class LogLevel { DEBUG, INFO, WARN, ERROR }
```

```kotlin
inline fun logD(
    tag: String,
    msg: () -> String,
) {
    DomainLogger.log(LogLevel.DEBUG, tag, throwable = null, context = LogContext(), msg = msg)
}

inline fun logI(
    tag: String,
    msg: () -> String,
) {
    DomainLogger.log(LogLevel.INFO, tag, throwable = null, context = LogContext(), msg = msg)
}
```

In `SecureFileLogSink`, add the override above `log(...)` and extend the logcat `when`:

```kotlin
    // Release sink: DEBUG is developer chatter (per-day sync narration, per-batch ingest counts) and
    // is dropped before the message lambda even runs -- DomainLogger.log checks isLoggable first.
    // INFO/WARN/ERROR still reach the encrypted diagnostic file.
    override fun isLoggable(
        level: LogLevel,
        tag: String,
    ): Boolean = level != LogLevel.DEBUG
```

```kotlin
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, formattedMessage)
            LogLevel.INFO -> Log.i(tag, formattedMessage)
            LogLevel.WARN -> Log.w(tag, formattedMessage, throwable)
            LogLevel.ERROR -> Log.e(tag, formattedMessage, throwable)
        }
```

In `HealthDashboardApplication.kt:160-164`:

```kotlin
                        when (level) {
                            LogLevel.DEBUG -> Log.d(tag, formatted)
                            LogLevel.INFO -> Log.i(tag, formatted)
                            LogLevel.WARN -> Log.w(tag, formatted, throwable)
                            LogLevel.ERROR -> Log.e(tag, formatted, throwable)
                        }
```

(The INFO branch moves from `Log.d` to `Log.i`; before this change every non-warn/error line landed
on `d`, which now belongs to DEBUG.)

- [ ] **Step 4: Compile the whole project to find every exhaustive `when (level)`**

Run: `./gradlew assembleDebug`
Expected: PASS. The enum addition makes the compiler report any non-exhaustive `when` — that is the
complete call-site checklist. The two known sites are handled above; the two test sinks
(`AppLogTest.TestLogSink`, `GetDashboardDataUseCaseTest`) do not switch on `level` exhaustively and
need no change.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
./gradlew ktlintFormat && ./gradlew testDebugUnitTest
git add core/model/src/main/kotlin/app/readylytics/health/domain/util/AppLog.kt \
        core/model/src/test/kotlin/app/readylytics/health/domain/util/AppLogTest.kt \
        app/src/main/kotlin/app/readylytics/health/util/SecureFileLogSink.kt \
        app/src/test/kotlin/app/readylytics/health/util/SecureFileLogSinkTest.kt \
        app/src/main/kotlin/app/readylytics/health/HealthDashboardApplication.kt
git commit -m "feat: add DEBUG log level and filter it out of the release diagnostic file (F2)"
```

---

### Task 4: Promote sync-lifecycle sites to INFO, and mark F2 done

**Files:**
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/ForegroundSyncController.kt:57,105,197,204`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/DailySyncUseCase.kt:58,150,159`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/ResyncRangeUseCase.kt:482,491,494,501,504`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthConnectRepositoryImpl.kt:134`
- Modify: `internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md`

**Interfaces:**
- Consumes: `logI` from Task 3.

- [ ] **Step 1: Promote the twelve sites**

Change `logD` → `logI` at exactly these call sites, leaving their messages untouched. Everything
else stays `logD` (that per-day/per-chunk narration is precisely the chatter F2 removes from the
release file).

| File | Line | Message | Why it survives into release diagnostics |
|---|---|---|---|
| `ForegroundSyncController.kt` | 57 | `"Sync disabled by user preference"` | The single most common "sync doesn't work" explanation. |
| `ForegroundSyncController.kt` | 105 | `"Catch-up window (...) exceeds the inline cap ..."` | Explains why a resync worker appeared. |
| `ForegroundSyncController.kt` | 197 | `"Sync requires historical resync, enqueuing worker"` | Same. |
| `ForegroundSyncController.kt` | 204 | `"Sync success"` | The positive terminal event; without it a report shows only failures. |
| `DailySyncUseCase.kt` | 58 | `"Starting sync (window=$windowDays days)..."` | Start marker + window size. |
| `DailySyncUseCase.kt` | 150 | `"Day $dayToScore: FAILED - ${result.reason}"` | Per-day *failures* only; `:146` SUCCESS stays DEBUG. |
| `DailySyncUseCase.kt` | 159 | `"Sync complete: $successCount succeeded, $failureCount failed"` | Terminal summary. |
| `ResyncRangeUseCase.kt` | 482 | `"Full resync complete ($totalDays days)"` / recompute-only variant | Terminal summary. |
| `ResyncRangeUseCase.kt` | 491 | `"Resync cancelled."` | A cancelled resync explains missing history. |
| `ResyncRangeUseCase.kt` | 494 | `"Resync stopped by Health Connect permission failure: ..."` | Actionable failure. |
| `ResyncRangeUseCase.kt` | 501 | `"Resync failed: window read timed out even at the minimum chunk size"` | Actionable failure. |
| `ResyncRangeUseCase.kt` | 504 | `"Resync failed with exception: ${e.message}"` | Actionable failure. |
| `HealthConnectRepositoryImpl.kt` | 134 | `"Missing permissions: $missing"` | The other most common "no data" explanation. |

Note that `ForegroundSyncController` calls the facade fully qualified
(`app.readylytics.health.domain.util.logD(...)`) at most sites and via an import-less receiver at
`:52-53` and `:203-204`. Preserve whichever form each site already uses — replace only the function
name. Where the file already imports `logD`, add the matching `logI` import.

- [ ] **Step 2: Verify nothing else regressed**

Run: `./gradlew testDebugUnitTest`
Expected: PASS. No test asserts on log levels from these classes; if one does, it will name the
class in the failure and the fix is to update the expected level.

- [ ] **Step 3: Mark F2 implemented in the performance plan**

In `internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md`:

- Under the `### F2.` heading, add an `**Implemented:**` line naming the four commit SHAs from this
  plan and stating the two shape changes vs the original write-up: rotation is a rename **plus** a
  one-time legacy re-encryption (the pre-check found `secureFileAssociatedData` *does* bind the
  filename), and slots are 512 KB × 12 rather than 2 MB × 3.
- In the §7 table, change row 3's status cell to `✅` with the SHAs.
- In the header status block, move F2 from "Not yet implemented" to "Landed".

- [ ] **Step 4: Final verification and commit**

```bash
./gradlew ktlintFormat && ./gradlew testDebugUnitTest && ./gradlew lintRelease
git add core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/ForegroundSyncController.kt \
        core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/DailySyncUseCase.kt \
        core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/ResyncRangeUseCase.kt \
        core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthConnectRepositoryImpl.kt \
        internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md
git commit -m "feat: keep sync lifecycle milestones at INFO in release diagnostics (F2)"
```

---

## Manual verification (release build, after Task 4)

The unit tests cannot cover the real Tink/Keystore path or the upgrade-in-place scenario. Run these
once on a device:

1. **Upgrade path.** Install the pre-F2 build, use the app until logs exist, then install this build
   over it (no uninstall). Trigger a sync, then export diagnostics from Settings. The exported file
   must decrypt and must still contain the *pre-upgrade* lines. This is the legacy-AD normalization
   working; if it silently returned empty, the old lines are gone.
2. **DEBUG filtering.** In the same export, confirm INFO/WARN/ERROR lines are present and no
   `[DEBUG]` line is. Confirm the sync milestones from Task 4's table *are* present.
3. **Rotation.** Force enough logging to exceed 512 KB (a full historical resync will do it) and
   confirm `cacheDir/logs` holds `prod_logs.txt` plus numbered backups, never more than 12 files,
   and that the export is still chronological.
4. **Cost.** Profile or systrace during a sync: the per-2-s multi-MB Tink work must be gone.

## Risks

- **Silent-loss failure mode.** Every decryption failure in this stack returns `""` rather than
  throwing (`SecureFileStore.kt:51-54`). A wrong associated data therefore looks like "the log file
  was empty", not like an error. Manual check 1 above is the only thing that catches it — do not
  skip it.
- **Transient over-retention after upgrade.** Migrated legacy slots keep their old 2 MB size until
  they rotate out, so `cacheDir/logs` can briefly hold ~10 MB instead of ~6 MB on upgraded installs.
  This is deliberate: the alternative (re-slicing during migration) deletes before it writes and can
  lose logs on a crash.
- **Durability window.** Raising the flush trigger to 64 lines / 5 s means slightly more pending
  lines are lost if the process dies. Accepted per the plan; `readLogsDecrypted()` always flushes
  first, so a user-initiated export never misses them.
