package com.gregor.lauritz.healthdashboard.widgets.glance

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.glance.state.GlanceStateDefinition
import java.io.File

object WidgetDataStoreProvider {
    private var dataStore: DataStore<Preferences>? = null

    fun getDataStore(context: Context): DataStore<Preferences> =
        dataStore ?: synchronized(this) {
            dataStore ?: context.glanceWidgetDataStore.also { dataStore = it }
        }
}

/**
 * Shared state definition for all widgets to ensure they use the same DataStore
 * as the SmallWidgetUpdater, MediumWidgetUpdater, and LargeWidgetUpdater.
 */
object WidgetGlanceStateDefinition : GlanceStateDefinition<Preferences> {
    override suspend fun getDataStore(
        context: Context,
        fileKey: String,
    ): DataStore<Preferences> = WidgetDataStoreProvider.getDataStore(context)

    override fun getLocation(
        context: Context,
        fileKey: String,
    ): File = context.preferencesDataStoreFile("glance_widget_data")
}

private val Context.glanceWidgetDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "glance_widget_data",
)
