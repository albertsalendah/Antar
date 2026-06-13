package com.richard_salendah.driverantar.ui.navigation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Bridges FCM notification taps received via MainActivity.onNewIntent()
 * (app backgrounded, not killed) to AppNavGraph, which observes [events]
 * and navigates.
 *
 * Cold-start taps are handled separately via MainActivity's existing
 * deepLinkRoute param to AppNavGraph — this handler only covers the
 * onNewIntent path (NOTIF-DEEPLINK).
 *
 * extraBufferCapacity = 4 mirrors the rider app's DeepLinkHandler (DEEP-1 fix).
 */
object DeepLinkHandler {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun emit(route: String) {
        _events.tryEmit(route)
    }
}