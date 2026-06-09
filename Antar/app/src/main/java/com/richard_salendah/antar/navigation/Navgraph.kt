package com.richard_salendah.antar.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.richard_salendah.antar.ui.auth.LoginScreen
import com.richard_salendah.antar.ui.auth.RegisterScreen
import com.richard_salendah.antar.ui.history.TripHistoryScreen
import com.richard_salendah.antar.ui.home.HomeScreen
import com.richard_salendah.antar.ui.profile.ProfileScreen
import com.richard_salendah.antar.ui.trip.ActiveTripScreen
import com.richard_salendah.antar.ui.trip.NegotiationScreen
import com.richard_salendah.antar.ui.trip.RateDriverScreen
import com.richard_salendah.antar.ui.trip.SearchingScreen
import com.richard_salendah.antar.ui.trip.TripCompleteScreen

@Composable
fun AntarNavGraph(
    navController: NavHostController,
    startDestination: String,
) {
    // ── FCM deep link observer ────────────────────────────────────────────────
    // DEEP-1: DeepLinkHandler uses extraBufferCapacity = 1. If two FCM
    // notifications arrive while the app is killed, only the last event
    // survives. This is acceptable for the current Talaud use-case where
    // simultaneous competing offers are unlikely, but would need a larger
    // buffer or persistent queue for higher-volume deployments.
    LaunchedEffect(navController) {
        DeepLinkHandler.events.collect { event ->
            when (event) {
                is DeepLinkEvent.ToNegotiation ->
                    navController.navigate(Screen.Negotiation.route(event.tripId)) {
                        launchSingleTop = true
                    }
                is DeepLinkEvent.ToActiveTrip ->
                    navController.navigate(Screen.ActiveTrip.route(event.tripId)) {
                        launchSingleTop = true
                    }
            }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {

        // ── Auth ──────────────────────────────────────────────────────────────
        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess       = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
                onNavigateToRegister = { navController.navigate(Screen.Register.route) },
            )
        }
        composable(Screen.Register.route) {
            RegisterScreen(
                onRegisterSuccess = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Register.route) { inclusive = true }
                    }
                },
                onNavigateToLogin = { navController.popBackStack() },
            )
        }

        // ── Main ──────────────────────────────────────────────────────────────
        composable(Screen.Home.route) {
            HomeScreen(
                onStartSearching  = { tripId ->
                    navController.navigate(Screen.Searching.route(tripId)) {
                        popUpTo(Screen.Home.route)
                    }
                },
                onOpenHistory     = { navController.navigate(Screen.History.route) },
                onOpenProfile     = { navController.navigate(Screen.Profile.route) },
                onActiveTripFound = { tripId, status ->
                    val dest = when (status) {
                        "requested"                        -> Screen.Searching.route(tripId)
                        "offered"                          -> Screen.Negotiation.route(tripId)
                        "agreed", "arrived", "in_progress" -> Screen.ActiveTrip.route(tripId)
                        else                               -> null
                    }
                    dest?.let { navController.navigate(it) { popUpTo(Screen.Home.route) } }
                },
            )
        }

        composable(Screen.History.route) {
            // RATE-DUP: onRateTrip now receives (tripId, onDone). We navigate
            // to RateDriver and call onDone() when that screen finishes so the
            // ViewModel can clear the in-flight state and mark the trip as rated
            // without requiring a full list refresh.
            TripHistoryScreen(
                onBack     = { navController.popBackStack() },
                onRateTrip = { tripId, onDone ->
                    navController.navigate(
                        Screen.RateDriver.route(tripId, fromHistory = true)
                    )
                    // Store the done callback so RateDriver can invoke it.
                    // We use the back-stack saved state handle as a lightweight
                    // one-shot communication channel between destinations.
                    navController.currentBackStackEntry
                        ?.savedStateHandle
                        ?.set("rate_done_callback_$tripId", true)
                    // Observe when RateDriver pops and call onDone.
                    // The back-stack entry for History is the previous entry
                    // after RateDriver is on top — we watch for its return.
                    navController.getBackStackEntry(Screen.History.route)
                        .savedStateHandle
                        .getLiveData<Boolean>("rated_$tripId")
                        .observeForever { rated ->
                            if (rated == true) {
                                onDone()
                                navController.getBackStackEntry(Screen.History.route)
                                    .savedStateHandle
                                    .remove<Boolean>("rated_$tripId")
                            }
                        }
                },
            )
        }

        composable(Screen.Profile.route) {
            ProfileScreen(
                onBack   = { navController.popBackStack() },
                onLogout = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        // ── Trip lifecycle ────────────────────────────────────────────────────
        composable(
            route     = Screen.Searching.route,
            arguments = listOf(navArgument("tripId") { type = NavType.StringType }),
        ) { back ->
            val tripId = back.arguments?.getString("tripId") ?: return@composable
            SearchingScreen(
                tripId          = tripId,
                onOfferReceived = {
                    navController.navigate(Screen.Negotiation.route(tripId)) {
                        popUpTo(Screen.Searching.route) { inclusive = true }
                    }
                },
                onTripCancelled = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route     = Screen.Negotiation.route,
            arguments = listOf(navArgument("tripId") { type = NavType.StringType }),
        ) { back ->
            val tripId = back.arguments?.getString("tripId") ?: return@composable
            NegotiationScreen(
                tripId          = tripId,
                onOfferAccepted = {
                    navController.navigate(Screen.ActiveTrip.route(tripId)) {
                        popUpTo(Screen.Negotiation.route) { inclusive = true }
                    }
                },
                onTripReset = {
                    navController.navigate(Screen.Searching.route(tripId)) {
                        popUpTo(Screen.Negotiation.route) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route     = Screen.ActiveTrip.route,
            arguments = listOf(navArgument("tripId") { type = NavType.StringType }),
        ) { back ->
            val tripId = back.arguments?.getString("tripId") ?: return@composable
            ActiveTripScreen(
                tripId          = tripId,
                onTripCompleted = {
                    navController.navigate(Screen.TripComplete.route(tripId)) {
                        popUpTo(Screen.ActiveTrip.route) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route     = Screen.TripComplete.route,
            arguments = listOf(navArgument("tripId") { type = NavType.StringType }),
        ) { back ->
            val tripId = back.arguments?.getString("tripId") ?: return@composable
            TripCompleteScreen(
                tripId = tripId,
                onRate = { navController.navigate(Screen.RateDriver.route(tripId, fromHistory = false)) },
                onSkip = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route     = Screen.RateDriver.route,
            arguments = listOf(
                navArgument("tripId")      { type = NavType.StringType },
                navArgument("fromHistory") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { back ->
            val tripId      = back.arguments?.getString("tripId")       ?: return@composable
            val fromHistory = back.arguments?.getBoolean("fromHistory") ?: false
            RateDriverScreen(
                tripId = tripId,
                onDone = {
                    if (fromHistory) {
                        // RATE-DUP: signal the History back-stack entry that
                        // this trip has been rated before popping back.
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set("rated_$tripId", true)
                        navController.popBackStack()
                    } else {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
            )
        }
    }
}