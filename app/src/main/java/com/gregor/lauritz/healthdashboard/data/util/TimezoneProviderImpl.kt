package com.gregor.lauritz.healthdashboard.data.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.gregor.lauritz.healthdashboard.domain.util.TimezoneProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimezoneProviderImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : TimezoneProvider {
        override val timezone: Flow<ZoneId> =
            callbackFlow {
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(
                            context: Context?,
                            intent: Intent?,
                        ) {
                            trySend(ZoneId.systemDefault())
                        }
                    }
                context.registerReceiver(receiver, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED))
                // Emit the current timezone immediately on collection start
                trySend(ZoneId.systemDefault())

                awaitClose {
                    context.unregisterReceiver(receiver)
                }
            }
    }
