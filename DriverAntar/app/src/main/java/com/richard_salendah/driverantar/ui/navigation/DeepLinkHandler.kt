package com.richard_salendah.driverantar.ui.navigation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Bridges FCM notification taps to AppNavGraph, which observes [events]
 * and navigates. Both cold-start (MainActivity.onCreate) and
 * backgrounded-not-killed (onNewIntent) paths emit here — there is a
 * single collector in AppNavGraph that waits for the NavHost backstack
 * before navigating, so both cases are handled uniformly.
 *
 * extraBufferCapacity = 4 mirrors the rider app's DeepLinkHandler (DEEP-1 fix).
 */
object DeepLinkHandler {
    private val _events = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun emit(route: String) { _events.tryEmit(route) }

    /**
     * Reset the replay cache after consuming an event so stale routes are not
     * re-delivered to collectors that subscribe later (e.g. after a back-nav).
     */
    fun consume() { _events.resetReplayCache() }
}