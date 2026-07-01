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
import com.richard_salendah.antar.ui.trip.CandidateReviewScreen
import com.richard_salendah.antar.ui.trip.NegotiationScreen
import com.richard_salendah.antar.ui.trip.NoDriverFoundScreen
import com.richard_salendah.antar.ui.trip.RateDriverScreen
import com.richard_salendah.antar.ui.trip.SearchingScreen
import com.richard_salendah.antar.ui.trip.TripCompleteScreen

@Composable
fun AntarNavGraph(
    navController: NavHostController,
    startDestination: String,
) {
    // ── FCM deep link observer ────────────────────────────────────────────────
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
                is DeepLinkEvent.ToCandidateReview ->
                    navController.navigate(
                        Screen.CandidateReview.route(event.tripId, event.reason)
                    ) {
                        launchSingleTop = true
                    }
                // Background FCM tap after driver withdrawal — navigate to NegotiationScreen
                // with reason="withdrew" so the VM pre-arms the dialog on load [R2, R7].
                is DeepLinkEvent.ToNegotiationWithReason ->
                    navController.navigate(
                        Screen.Negotiation.route(event.tripId, event.reason)
                    ) {
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
                    navController.navigate(Screen.CandidateReview.route(tripId)) {
                        popUpTo(Screen.Home.route)
                    }
                },
                onOpenHistory     = { navController.navigate(Screen.History.route) },
                onOpenProfile     = { navController.navigate(Screen.Profile.route) },
                onActiveTripFound = { tripId, status ->
                    val dest = when (status) {
                        "requested"                        -> Screen.CandidateReview.route(tripId)
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

        // CandidateReview: primary post-booking destination.
        // Optional reason arg: "declined" when arriving via background FCM tap
        // after a driver decline — dialog is pre-armed in the ViewModel [R1, R7].
        composable(
            route     = Screen.CandidateReview.route,
            arguments = listOf(
                navArgument("tripId") { type = NavType.StringType },
                navArgument("reason") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { back ->
            val tripId = back.arguments?.getString("tripId") ?: return@composable
            val reason = back.arguments?.getString("reason") ?: ""
            CandidateReviewScreen(
                tripId          = tripId,
                initialReason   = reason,
                onOfferReceived = {
                    navController.navigate(Screen.Negotiation.route(tripId)) {
                        popUpTo(Screen.CandidateReview.route) { inclusive = true }
                    }
                },
                onNoDriverFound = {
                    navController.navigate(Screen.NoDriverFound.route(tripId)) {
                        popUpTo(Screen.CandidateReview.route) { inclusive = true }
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
            route     = Screen.NoDriverFound.route,
            arguments = listOf(navArgument("tripId") { type = NavType.StringType }),
        ) { back ->
            val tripId = back.arguments?.getString("tripId") ?: return@composable
            NoDriverFoundScreen(
                tripId             = tripId,
                onDriverReselected = {
                    navController.navigate(Screen.CandidateReview.route(tripId)) {
                        popUpTo(Screen.NoDriverFound.route) { inclusive = true }
                    }
                },
                onTripCancelled    = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        // Searching: kept for legacy deep-link compatibility only.
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

        // Negotiation: optional reason arg "withdrew" pre-arms the withdrew dialog
        // on load when arriving via background FCM tap [R2, R7].
        composable(
            route     = Screen.Negotiation.route,
            arguments = listOf(
                navArgument("tripId") { type = NavType.StringType },
                navArgument("reason") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { back ->
            val tripId = back.arguments?.getString("tripId") ?: return@composable
            val reason = back.arguments?.getString("reason") ?: ""
            NegotiationScreen(
                tripId          = tripId,
                initialReason   = reason,
                onOfferAccepted = {
                    navController.navigate(Screen.ActiveTrip.route(tripId)) {
                        popUpTo(Screen.Negotiation.route) { inclusive = true }
                    }
                },
                // onTripReset: driver withdrew → Continue → approve-candidate succeeded.
                // Also used when rider rejects → trip resets → back to CandidateReview.
                onTripReset     = {
                    navController.navigate(Screen.CandidateReview.route(tripId)) {
                        popUpTo(Screen.Negotiation.route) { inclusive = true }
                    }
                },
                onNoDriverFound = {
                    navController.navigate(Screen.NoDriverFound.route(tripId)) {
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
