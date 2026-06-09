package com.richard_salendah.antar.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.richard_salendah.antar.ui.auth.LoginScreen
import com.richard_salendah.antar.ui.auth.RegisterScreen
import com.richard_salendah.antar.ui.history.TripHistoryScreen
import com.richard_salendah.antar.ui.history.TripHistoryViewModel
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
    // DEEP-1 fixed: extraBufferCapacity=4 ensures FCM tap events survive cold-start
    // before the NavGraph collector subscribes. tryEmit buffers up to 4 events;
    // overflow (5+ simultaneous taps) is still dropped but unrealistic for Talaud volume.
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
            val historyVm: TripHistoryViewModel = viewModel()
            val savedState = it.savedStateHandle

            // RATE-DUP: watch for rating completions written by RateDriver on pop.
            // StateFlow collection is scoped to this composable — cancelled automatically
            // on dispose, replacing the previous observeForever which leaked the observer.
            LaunchedEffect(Unit) {
                savedState.getStateFlow("last_rated_trip", "").collect { tripId ->
                    if (tripId.isNotBlank()) {
                        historyVm.onRatingDone(tripId)
                        savedState["last_rated_trip"] = ""
                    }
                }
            }

            TripHistoryScreen(
                onBack     = { navController.popBackStack() },
                onRateTrip = { tripId, _ ->
                    // onDone callback not used here — RateDriver signals back via
                    // savedStateHandle "last_rated_trip" key on pop instead.
                    historyVm.markRatingInFlight(tripId)
                    navController.navigate(Screen.RateDriver.route(tripId, fromHistory = true))
                },
                viewModel  = historyVm,
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
                        // RATE-DUP: signal History via a single "last_rated_trip" key
                        // so TripHistoryViewModel.onRatingDone() is called on pop.
                        // Replaced the previous per-trip "rated_$tripId" key + observeForever.
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set("last_rated_trip", tripId)
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