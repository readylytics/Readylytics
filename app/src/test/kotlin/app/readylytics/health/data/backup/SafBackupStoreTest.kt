package app.readylytics.health.data.backup

import android.content.Context
import android.net.Uri
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
class SafBackupStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun list_returnsOnlyValidBackupFilesSortedDescending() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val backupDir = tempFolder.newFolder("saf_backups")
            val store = SafBackupStore(context, Uri.fromFile(backupDir))

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
            assertEquals("backup_2026_01_01.zip", list[1].name)
        }

    @Test
    fun publish_newBackup_createsTargetAndCleansUpStaged() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val backupDir = tempFolder.newFolder("saf_backups")
            val store = SafBackupStore(context, Uri.fromFile(backupDir))

            val source =
                tempFolder.newFile("source.zip").apply {
                    writeText("initial payload")
                }
            val targetName = "backup_2026_01_01.zip"

            store.publish(source, targetName)

            val target = File(backupDir, targetName)
            assertTrue(target.exists())
            assertEquals("initial payload", target.readText())
            assertFalse(File(backupDir, "$targetName.rotating").exists())
            assertFalse(File(backupDir, "$targetName.bak").exists())
        }

    @Test
    fun publish_existingBackup_replacesTargetViaBakAndCleansUpBak() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val backupDir = tempFolder.newFolder("saf_backups")
            val store = SafBackupStore(context, Uri.fromFile(backupDir))

            val targetName = "backup_2026_01_01.zip"
            val target =
                File(backupDir, targetName).apply {
                    writeText("original payload")
                }

            val source =
                tempFolder.newFile("source.zip").apply {
                    writeText("updated payload")
                }

            store.publish(source, targetName)

            assertTrue(target.exists())
            assertEquals("updated payload", target.readText())
            assertFalse(File(backupDir, "$targetName.rotating").exists())
            assertFalse(File(backupDir, "$targetName.bak").exists())
        }

    @Test
    fun publish_emptySource_throwsIllegalStateException() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val backupDir = tempFolder.newFolder("saf_backups")
            val store = SafBackupStore(context, Uri.fromFile(backupDir))

            val source = tempFolder.newFile("empty.zip")

            assertFailsWith<IllegalStateException> {
                store.publish(source, "backup_empty.zip")
            }
        }

    @Test
    fun read_returnsInputStreamWithContent() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val backupDir = tempFolder.newFolder("saf_backups")
            val store = SafBackupStore(context, Uri.fromFile(backupDir))

            val file =
                File(backupDir, "backup_test.zip").apply {
                    writeText("saf payload")
                }
            val stream = store.read(BackupLocation(Uri.fromFile(file).toString()))
            val content = stream.bufferedReader().use { it.readText() }
            assertEquals("saf payload", content)
        }

    @Test
    fun delete_removesFileSuccessfully() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val backupDir = tempFolder.newFolder("saf_backups")
            val store = SafBackupStore(context, Uri.fromFile(backupDir))

            val file =
                File(backupDir, "backup_delete.zip").apply {
                    writeText("to be deleted")
                }
            assertTrue(file.exists())

            store.delete(BackupLocation(Uri.fromFile(file).toString()))
            assertFalse(file.exists())
        }

    @Test
    fun prune_removesExpiredBackupsOnly() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val backupDir = tempFolder.newFolder("saf_backups")
            val store = SafBackupStore(context, Uri.fromFile(backupDir))

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
