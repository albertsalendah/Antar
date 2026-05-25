package com.richard_salendah.driverantar.ui.navigation

sealed class Screen(val route: String) {

    object Login    : Screen("auth/login")
    object Register : Screen("auth/register")
    object Map      : Screen("map")
    object Profile  : Screen("profile")
    object AddVehicle : Screen("add_vehicle")
    object Earnings   : Screen("earnings")
    object IncomingTrips : Screen("trips/incoming")

    object OfferPrice : Screen("trips/offer/{tripId}/{defaultFare}/{tripType}") {
        fun route(tripId: String, defaultFare: Double, tripType: String) =
            "trips/offer/$tripId/$defaultFare/$tripType"
    }

    // defaultFare added — admin floor must survive the full navigation round-trip
    // so CounterDecision always enforces the correct minimum, not the driver's offer.
    object WaitingForRider : Screen("trips/waiting/{tripId}/{offeredFare}/{defaultFare}") {
        fun route(tripId: String, offeredFare: Double, defaultFare: Double) =
            "trips/waiting/$tripId/$offeredFare/$defaultFare"
    }

    object CounterDecision : Screen(
        "trips/counter/{tripId}/{riderFare}/{defaultFare}/{driverCounterCount}/{maxDriverCounters}"
    ) {
        fun route(
            tripId: String,
            riderFare: Double,
            defaultFare: Double,
            driverCounterCount: Int,
            maxDriverCounters: Int
        ) = "trips/counter/$tripId/$riderFare/$defaultFare/$driverCounterCount/$maxDriverCounters"
    }

    object ActiveTrip : Screen("trips/active/{tripId}") {
        fun route(tripId: String) = "trips/active/$tripId"
    }

    object RateRider : Screen("trips/rate/{tripId}") {
        fun route(tripId: String) = "trips/rate/$tripId"
    }

    object TripHistory : Screen("trips/history")
}