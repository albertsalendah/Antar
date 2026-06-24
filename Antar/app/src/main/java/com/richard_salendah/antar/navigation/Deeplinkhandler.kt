package com.richard_salendah.antar.navigation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// ── Events ────────────────────────────────────────────────────────────────────

sealed class DeepLinkEvent {
    /** Driver submitted/countered an offer — go to NegotiationScreen */
    data class ToNegotiation(val tripId: String) : DeepLinkEvent()
    /** Rider's offer was accepted — go to ActiveTripScreen */
    data class ToActiveTrip(val tripId: String) : DeepLinkEvent()
    data class ToCandidateReview(val tripId: String) : DeepLinkEvent()
}

// ── Handler singleton ─────────────────────────────────────────────────────────

/**
 * Bridges FCM/notification tap events (from Service or Activity) to
 * the NavGraph which observes [events] and performs the navigation.
 *
 * extraBufferCapacity = 1 ensures an event emitted before the NavGraph
 * subscribes is not lost (e.g. cold start from notification tap).
 */
object DeepLinkHandler {
    private val _events = MutableSharedFlow<DeepLinkEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<DeepLinkEvent> = _events.asSharedFlow()

    fun emit(event: DeepLinkEvent) {
        _events.tryEmit(event)
    }
}