package com.richard_salendah.antar.navigation

sealed class Screen(val route: String) {

    // ── Auth ──────────────────────────────────────────────────────────────────
    object Login    : Screen("login")
    object Register : Screen("register")

    // ── Main ──────────────────────────────────────────────────────────────────
    object Home    : Screen("home")
    object History : Screen("history")
    object Profile : Screen("profile")

    // ── Trip lifecycle ────────────────────────────────────────────────────────

    // CandidateReview replaces Searching as the primary post-booking destination.
    // Rider reviews the suggested driver, approves/rejects, then waits for the
    // driver to make an offer. Searching is kept for legacy deep-link compatibility.
    object CandidateReview : Screen("candidate_review/{tripId}?reason={reason}") {
        fun route(tripId: String, reason: String = "") = "candidate_review/$tripId?reason=$reason"
    }
    object NoDriverFound : Screen("no_driver_found/{tripId}") {
        fun route(tripId: String) = "no_driver_found/$tripId"
    }
    object Searching : Screen("searching/{tripId}") {
        fun route(tripId: String) = "searching/$tripId"
    }
    object Negotiation : Screen("negotiation/{tripId}?reason={reason}") {
        fun route(tripId: String, reason: String = "") = "negotiation/$tripId?reason=$reason"
    }
    object ActiveTrip : Screen("active_trip/{tripId}") {
        fun route(tripId: String) = "active_trip/$tripId"
    }
    object TripComplete : Screen("trip_complete/{tripId}") {
        fun route(tripId: String) = "trip_complete/$tripId"
    }

    // fromHistory=true → popBackStack (return to history)
    // fromHistory=false → clear stack to Home (default, post-trip flow)
    object RateDriver : Screen("rate_driver/{tripId}?fromHistory={fromHistory}") {
        fun route(tripId: String, fromHistory: Boolean = false) =
            "rate_driver/$tripId?fromHistory=$fromHistory"
    }
}