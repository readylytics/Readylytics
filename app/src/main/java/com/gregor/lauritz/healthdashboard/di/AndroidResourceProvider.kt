package com.gregor.lauritz.healthdashboard.di

import android.content.Context
import com.gregor.lauritz.healthdashboard.domain.util.ResourceProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class AndroidResourceProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ResourceProvider {
        override fun getString(resId: Int): String = context.getString(resId)

        override fun getString(
            resId: Int,
            vararg formatArgs: Any,
        ): String = context.getString(resId, *formatArgs)
    }
