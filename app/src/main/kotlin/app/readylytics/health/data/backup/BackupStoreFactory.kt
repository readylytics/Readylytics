package app.readylytics.health.data.backup

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

interface BackupStoreFactory {
    fun create(customUri: Uri?): BackupStore

    fun createDefault(): BackupStore
}

@Singleton
class DefaultBackupStoreFactory
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : BackupStoreFactory {
        override fun create(customUri: Uri?): BackupStore =
            when {
                customUri == null -> FileBackupStore(context)
                customUri.scheme == "file" -> FileBackupStore(context, File(customUri.path!!))
                else -> SafBackupStore(context, customUri)
            }

        override fun createDefault(): BackupStore = FileBackupStore(context)
    }
