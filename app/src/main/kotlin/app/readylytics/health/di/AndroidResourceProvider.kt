package app.readylytics.health.di

import android.content.Context
import app.readylytics.health.domain.util.ResourceProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class AndroidResourceProvider
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : ResourceProvider {
        override fun getString(resId: Int): String = context.getString(resId)

        override fun getString(
            resId: Int,
            vararg formatArgs: Any,
        ): String = context.getString(resId, *formatArgs)
    }
