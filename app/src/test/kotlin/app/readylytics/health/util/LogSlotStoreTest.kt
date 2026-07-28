package app.readylytics.health.util

import app.readylytics.health.data.security.SecureFileStore
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

        val slots = fake.storedPlaintextByName(directory)
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

        val finalContent = subject.readAll()
        assertTrue(
            "Migrated active-slot content must stay decryptable after a rename",
            finalContent.contains("old-active"),
        )
        assertTrue(
            "Migrated backup-slot content must stay decryptable after the backup-chain rename",
            finalContent.contains("old-backup"),
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

    @Test
    fun constantAssociatedDataAndActiveFileNameArePinned() {
        val directory = tempFolder.newFolder("logs")
        val fake = AdAwareSecureFileStore()
        val subject = store(directory, secureFileStore = fake)

        subject.appendLines(listOf(line("pin-me")))

        // A future refactor that alters either value makes every existing user's slots unreadable
        // (wrong AD) or undiscoverable (wrong active filename), while the suite stays green unless
        // something asserts the literal.
        assertEquals("prod_logs.txt", LogSlotStore.ACTIVE_FILE_NAME)
        assertEquals(
            listOf("readylytics_prod_logs"),
            fake.writtenAssociatedData.distinct(),
        )
    }

    @Test
    fun reNormalizingAfterMarkerEvictionLeavesContentUnchanged() {
        val directory = tempFolder.newFolder("logs")
        val fake = AdAwareSecureFileStore()
        val original = store(directory, maxFileSize = 10L, secureFileStore = fake)
        original.appendLines(listOf(line("aaaaa")))
        original.appendLines(listOf(line("bbbbb")))
        original.appendLines(listOf(line("ccccc")))
        val expected = original.readAll()

        // Android's idle cache trimming deletes individual cacheDir files by atime; a tiny,
        // never-touched marker is a prime eviction candidate independent of the slots it guards.
        assertTrue(File(directory, ".slot_ad_v2").delete())

        val reopened = store(directory, maxFileSize = 10L, secureFileStore = fake)
        assertEquals(
            "Re-running normalization on already-converted slots must be a no-op",
            expected,
            reopened.readAll(),
        )
    }

    private fun legacyAd(name: String): ByteArray = name.toByteArray(Charsets.UTF_8)

    /**
     * Models Tink's AEAD binding: content written under one associatedData cannot be read back
     * under a different one. The production store swallows that failure and returns "" — this fake
     * does the same, and records it so a test can assert it never happened.
     *
     * Content is keyed by a per-write token that is written into the on-disk placeholder, so it
     * travels with the bytes when [LogSlotStore] renames a slot — exactly like real ciphertext.
     * Keying by filename does NOT work here: the store always writes the active slot under the same
     * name and renames it away afterwards, so two seals with no read in between would collide on a
     * single map entry and the older slot's content would vanish from the fake while its file is
     * still on disk. (The whole point of F2 is that nothing reads between seals.)
     */
    private class AdAwareSecureFileStore : SecureFileStore {
        private data class Entry(
            val content: String,
            val associatedData: String,
        )

        private val entries = linkedMapOf<String, Entry>()
        private var nextToken = 0
        val writtenNames = mutableListOf<String>()
        val writtenAssociatedData = mutableListOf<String>()
        val readsThatFailedAuthentication = mutableListOf<String>()

        override fun readText(
            file: File,
            associatedData: ByteArray,
        ): String {
            val token = tokenOf(file) ?: return ""
            val entry = entries[token] ?: return ""
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
            writtenAssociatedData += associatedData.toString(Charsets.UTF_8)
            val token = "t${nextToken++}"
            entries[token] = Entry(content, associatedData.toString(Charsets.UTF_8))
            file.parentFile?.mkdirs()
            file.writeText(CIPHERTEXT_PREFIX + token)
        }

        /**
         * Plaintext of every slot currently present in [directory], keyed by its current filename.
         * Files this fake never wrote (the migration marker, raw garbage) resolve to no token and
         * are skipped — the same way an undecryptable file reads back as "".
         */
        fun storedPlaintextByName(directory: File): Map<String, String> =
            directory
                .listFiles()
                .orEmpty()
                .sortedBy { it.name }
                .mapNotNull { file ->
                    tokenOf(file)?.let { entries[it] }?.let { file.name to it.content }
                }.toMap()

        private fun tokenOf(file: File): String? =
            if (file.exists()) file.readText().removePrefix(CIPHERTEXT_PREFIX) else null

        private companion object {
            const val CIPHERTEXT_PREFIX = "ciphertext:"
        }
    }
}
