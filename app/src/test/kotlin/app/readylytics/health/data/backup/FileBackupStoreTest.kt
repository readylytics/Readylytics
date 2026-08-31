package app.readylytics.health.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.model.domain.backup.BackupLocation
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class FileBackupStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun list_returnsOnlyValidBackupFilesSortedDescending() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val backupDir = tempFolder.newFolder("backups")
            val store = FileBackupStore(context, backupDir)

            File(backupDir, "backup_2026_01_01.zip").apply {
                writeText("data1")
                setLastModified(1000L)
            }
            File(backupDir, "backup_2026_01_02.zip").apply {
                writeText("data2")
                setLastModified(2000L)
            }
            File(backupDir, "backup_ignored.txt").writeText("ignored")
            File(backupDir, "random.zip").writeText("ignored")

            val list = store.list()
            assertEquals(2, list.size)
            assertEquals("backup_2026_01_02.zip", list[0].name)
            assertEquals(2000L, list[0].lastModified)
            assertEquals("backup_2026_01_01.zip", list[1].name)
            assertEquals(1000L, list[1].lastModified)
        }

    @Test
    fun read_returnsInputStreamWithContent() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val backupDir = tempFolder.newFolder("backups")
            val store = FileBackupStore(context, backupDir)

            val file =
                File(backupDir, "backup_test.zip").apply {
                    writeText("test payload")
                }
            val stream = store.read(BackupLocation(file.toURI().toString()))
            val content = stream.bufferedReader().use { it.readText() }
            assertEquals("test payload", content)
        }

    @Test
    fun publish_movesSourceToTargetInBackupDir() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val backupDir = tempFolder.newFolder("backups")
            val store = FileBackupStore(context, backupDir)

            val source =
                tempFolder.newFile("source.zip").apply {
                    writeText("fresh payload")
                }
            val targetName = "backup_2026_01_01.zip"

            store.publish(source, targetName)

            assertFalse(source.exists())
            val target = File(backupDir, targetName)
            assertTrue(target.exists())
            assertEquals("fresh payload", target.readText())
            assertFalse(File(backupDir, "$targetName.tmp").exists())
        }

    @Test
    fun publish_emptySource_throwsIllegalStateException() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val backupDir = tempFolder.newFolder("backups")
            val store = FileBackupStore(context, backupDir)

            val source = tempFolder.newFile("empty.zip")

            assertFailsWith<IllegalStateException> {
                store.publish(source, "backup_empty.zip")
            }
        }

    @Test
    fun publish_failedRename_preservesExistingTarget() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val backupDir = tempFolder.newFolder("backups_failed_rename")
            val store = FileBackupStore(context, backupDir)

            val targetName = "backup_2026_01_01.zip"
            val targetDir =
                File(backupDir, targetName).apply {
                    mkdirs()
                    File(this, "inner.txt").writeText("cannot overwrite directory with file")
                }

            val source =
                tempFolder.newFile("source_failed.zip").apply {
                    writeText("new payload")
                }

            assertFailsWith<IllegalStateException> {
                store.publish(source, targetName)
            }

            assertTrue(targetDir.exists(), "Existing target must not be deleted when rename fails")
            assertFalse(File(backupDir, "$targetName.tmp").exists(), "Temporary file must be cleaned up")
        }

    @Test
    fun delete_removesFileSuccessfully() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val backupDir = tempFolder.newFolder("backups")
            val store = FileBackupStore(context, backupDir)

            val file =
                File(backupDir, "backup_delete.zip").apply {
                    writeText("to be deleted")
                }
            assertTrue(file.exists())

            store.delete(BackupLocation(file.toURI().toString()))
            assertFalse(file.exists())
        }

    @Test
    fun prune_removesExpiredBackupsOnly() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val backupDir = tempFolder.newFolder("backups")
            val store = FileBackupStore(context, backupDir)

            val now = System.currentTimeMillis()
            val oldFile =
                File(backupDir, "backup_old.zip").apply {
                    writeText("old")
                    setLastModified(now - 100_000L)
                }
            val recentFile =
                File(backupDir, "backup_recent.zip").apply {
                    writeText("recent")
                    setLastModified(now - 10_000L)
                }

            store.prune(retentionPeriodMs = 50_000L)

            assertFalse(oldFile.exists())
            assertTrue(recentFile.exists())
        }
}
