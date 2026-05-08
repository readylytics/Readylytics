package com.gregor.lauritz.healthdashboard.widgets.glance

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

object WidgetDataStoreProvider {
    private var dataStore: DataStore<Preferences>? = null

    fun getDataStore(context: Context): DataStore<Preferences> {
        return dataStore ?: synchronized(this) {
            dataStore ?: context.glanceWidgetDataStore.also { dataStore = it }
        }
    }
}

private val Context.glanceWidgetDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "glance_widget_data"
)
