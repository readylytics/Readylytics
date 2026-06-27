package app.readylytics.health.data.security

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeyMetadataStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val prefs = context.getSharedPreferences("key_metadata", Context.MODE_PRIVATE)

        fun currentVersion(): Int = prefs.getInt(KEY_VERSION, 1)

        fun strongBoxBacked(): Boolean = prefs.getBoolean(STRONGBOX_BACKED, false)

        fun setCurrentKey(
            version: Int,
            strongBoxBacked: Boolean,
        ) {
            prefs.edit {
                putInt(KEY_VERSION, version)
                putBoolean(STRONGBOX_BACKED, strongBoxBacked)
            }
        }

        private companion object {
            const val KEY_VERSION = "key_version"
            const val STRONGBOX_BACKED = "strongbox_backed"
        }
    }
