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
    /** Candidate assigned or changed — go to CandidateReviewScreen.
     *  reason = "declined" when arriving via background FCM tap after a driver decline [R1, R7] */
    data class ToCandidateReview(val tripId: String, val reason: String = "") : DeepLinkEvent()
    /** Driver withdrew their price offer — go to NegotiationScreen with popup pre-armed [R2, R7] */
    data class ToNegotiationWithReason(val tripId: String, val reason: String) : DeepLinkEvent()
}

// ── Handler singleton ─────────────────────────────────────────────────────────

/**
 * Bridges FCM/notification tap events (from Service or Activity) to
 * the NavGraph which observes [events] and performs the navigation.
 *
 * extraBufferCapacity = 4 ensures an event emitted before the NavGraph
 * subscribes is not lost (e.g. cold start from notification tap).
 */
object DeepLinkHandler {
    private val _events = MutableSharedFlow<DeepLinkEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<DeepLinkEvent> = _events.asSharedFlow()

    fun emit(event: DeepLinkEvent) {
        _events.tryEmit(event)
    }
}
