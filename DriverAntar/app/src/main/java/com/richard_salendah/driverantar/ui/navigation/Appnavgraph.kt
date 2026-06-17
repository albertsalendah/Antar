package com.richard_salendah.driverantar.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.richard_salendah.driverantar.data.remote.DriverRepository
import com.richard_salendah.driverantar.ui.earnings.EarningsScreen
import com.richard_salendah.driverantar.ui.earnings.EarningsViewModel
import com.richard_salendah.driverantar.ui.auth.AuthState
import com.richard_salendah.driverantar.ui.auth.AuthViewModel
import com.richard_salendah.driverantar.ui.auth.LoginScreen
import com.richard_salendah.driverantar.ui.auth.RegisterScreen
import com.richard_salendah.driverantar.ui.map.MapScreen
import com.richard_salendah.driverantar.ui.map.MapViewModel
import com.richard_salendah.driverantar.ui.profile.ProfileScreen
import com.richard_salendah.driverantar.ui.profile.ProfileViewModel
import com.richard_salendah.driverantar.ui.trip.CounterDecisionScreen
import com.richard_salendah.driverantar.ui.trip.CounterDecisionViewModel
import com.richard_salendah.driverantar.ui.trip.IncomingTripsScreen
import com.richard_salendah.driverantar.ui.trip.IncomingTripsViewModel
import com.richard_salendah.driverantar.ui.trip.OfferPriceScreen
import com.richard_salendah.driverantar.ui.trip.OfferPriceViewModel
import com.richard_salendah.driverantar.ui.trip.TripSelectionHolder
import com.richard_salendah.driverantar.ui.trip.ActiveTripScreen
import com.richard_salendah.driverantar.ui.trip.ActiveTripViewModel
import com.richard_salendah.driverantar.ui.trip.RateRiderScreen
import com.richard_salendah.driverantar.ui.trip.RateRiderViewModel
import com.richard_salendah.driverantar.ui.trip.TripHistoryScreen
import com.richard_salendah.driverantar.ui.trip.TripHistoryViewModel
import com.richard_salendah.driverantar.ui.trip.WaitingForRiderScreen
import com.richard_salendah.driverantar.ui.trip.WaitingForRiderViewModel
import com.richard_salendah.driverantar.ui.vehicle.AddVehicleScreen
import com.richard_salendah.driverantar.ui.vehicle.VehicleViewModel
import com.richard_salendah.driverantar.utils.SessionManager
import com.richard_salendah.driverantar.ui.service.LocationService
import kotlinx.coroutines.flow.first

@Composable
fun AppNavGraph(
    navController: NavHostController,
    repository: DriverRepository,
) {
    val startDestination = if (SessionManager.isLoggedIn) Screen.Map.route
    else Screen.Login.route

    val authViewModel = remember { AuthViewModel(repository) }
    val mapViewModel  = remember { MapViewModel(repository) }

    LaunchedEffect(navController) {
        DeepLinkHandler.events.collect { route ->
            if (SessionManager.isLoggedIn) {
                // Wait for NavHost to initialise its first destination before navigating.
                // Without this, cold-start navigation silently fails on some Android versions
                // because the back stack is empty when the LaunchedEffect first fires.
                navController.currentBackStackEntryFlow.first { true }
                navController.navigate(route) { launchSingleTop = true }
                DeepLinkHandler.consume()
            }
        }
    }

    // NOTIF-DEEPLINK: handles FCM taps while the app is backgrounded but not
    // killed — onNewIntent() emits here since onCreate's deepLinkRoute won't re-fire.
    LaunchedEffect(navController) {
        DeepLinkHandler.events.collect { route ->
            if (SessionManager.isLoggedIn) {
                navController.navigate(route) { launchSingleTop = true }
            }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {

        // ── Auth ──────────────────────────────────────────────────────────────

        composable(Screen.Login.route) {
            val state = authViewModel.state
            LaunchedEffect(state) {
                if (state is AuthState.LoginSuccess) {
                    authViewModel.resetState()
                    navController.navigate(Screen.Map.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            }
            LoginScreen(
                viewModel            = authViewModel,
                onNavigateToRegister = { navController.navigate(Screen.Register.route) }
            )
        }

        composable(Screen.Register.route) {
            val state = authViewModel.state
            LaunchedEffect(state) {
                if (state is AuthState.LoginSuccess) {
                    authViewModel.resetState()
                    navController.navigate(Screen.Map.route) {
                        popUpTo(Screen.Register.route) { inclusive = true }
                    }
                }
            }
            RegisterScreen(
                viewModel         = authViewModel,
                onNavigateToLogin = { navController.popBackStack() }
            )
        }

        // ── Map ───────────────────────────────────────────────────────────────

        composable(Screen.Map.route) {
            MapScreen(
                viewModel           = mapViewModel,
                onOpenProfile       = { navController.navigate(Screen.Profile.route) },
                onAddVehicle        = { navController.navigate(Screen.Profile.route) },
                onNavigateToTrip    = { route ->
                    navController.navigate(route) { launchSingleTop = true }
                },
                onOpenIncomingTrips = { navController.navigate(Screen.IncomingTrips.route) }
            )
        }

        // ── Profile ───────────────────────────────────────────────────────────

        composable(Screen.Profile.route) {
            val profileViewModel = remember { ProfileViewModel(repository) }
            val vehicleViewModel = remember { VehicleViewModel(repository) }
            var showAddVehicle by remember { mutableStateOf(false) }

            if (showAddVehicle) {
                AddVehicleScreen(
                    viewModel = vehicleViewModel,
                    onBack    = { showAddVehicle = false },
                    onSuccess = { showAddVehicle = false; profileViewModel.load() }
                )
            } else {
                val logoutContext = androidx.compose.ui.platform.LocalContext.current
                ProfileScreen(
                    viewModel         = profileViewModel,
                    onBack            = { navController.popBackStack() },
                    onAddVehicle      = { showAddVehicle = true },
                    onLogout          = {
                        logoutContext.stopService(
                            android.content.Intent(logoutContext,
                                com.richard_salendah.driverantar.ui.service.LocationService::class.java)
                        )
                        SessionManager.clear()
                        navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onOpenEarnings    = { navController.navigate(Screen.Earnings.route) },
                    onOpenTripHistory = { navController.navigate(Screen.TripHistory.route) }
                )
            }
        }

        // ── Earnings ──────────────────────────────────────────────────────────

        composable(Screen.Earnings.route) {
            val vm = remember { EarningsViewModel(repository) }
            EarningsScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }

        // ── Incoming trips ────────────────────────────────────────────────────

        composable(Screen.IncomingTrips.route) {
            val vm = remember { IncomingTripsViewModel(repository) }
            IncomingTripsScreen(
                viewModel      = vm,
                navController  = navController,
                onBack         = { navController.popBackStack() },
                onTripSelected = { trip ->
                    if (!LocationService.isRunning) {
                        vm.showSnack("Aktifkan mode online terlebih dahulu")
                    } else {
                        TripSelectionHolder.selectedTrip = trip
                        navController.navigate(
                            Screen.OfferPrice.route(trip.id, trip.default_fare, trip.trip_type)
                        )
                    }
                }
            )
        }

        // ── Offer price ───────────────────────────────────────────────────────

        composable(
            route     = Screen.OfferPrice.route,
            arguments = listOf(
                navArgument("tripId")      { type = NavType.StringType },
                navArgument("defaultFare") { type = NavType.StringType },
                navArgument("tripType")    { type = NavType.StringType }
            )
        ) { backStack ->
            val tripId      = backStack.arguments?.getString("tripId")                        ?: return@composable
            val defaultFare = backStack.arguments?.getString("defaultFare")?.toDoubleOrNull() ?: 0.0
            val tripType    = backStack.arguments?.getString("tripType")                       ?: "transport"
            val trip        = TripSelectionHolder.selectedTrip                                 ?: return@composable

            // Clear holder on dispose so a config change doesn't leave a stale reference
            DisposableEffect(Unit) { onDispose { TripSelectionHolder.selectedTrip = null } }

            val vm = remember { OfferPriceViewModel(repository, tripId, defaultFare, tripType) }
            OfferPriceScreen(
                viewModel  = vm,
                trip       = trip,
                onBack     = {
                    TripSelectionHolder.selectedTrip = null
                    navController.popBackStack()
                },
                onOfferSubmitted = { id ->
                    TripSelectionHolder.selectedTrip = null
                    // Pass defaultFare (admin floor) alongside offeredFare so it
                    // survives the full WaitingForRider → CounterDecision round-trip.
                    navController.navigate(
                        Screen.WaitingForRider.route(id, vm.currentFare, defaultFare)
                    ) {
                        popUpTo(Screen.OfferPrice.route) { inclusive = true }
                    }
                }
            )
        }

        // ── Waiting for rider ─────────────────────────────────────────────────

        composable(
            route     = Screen.WaitingForRider.route,
            arguments = listOf(
                navArgument("tripId")      { type = NavType.StringType },
                navArgument("offeredFare") { type = NavType.StringType },
                navArgument("defaultFare") { type = NavType.StringType }
            )
        ) { backStack ->
            val tripId      = backStack.arguments?.getString("tripId")                        ?: return@composable
            val offeredFare = backStack.arguments?.getString("offeredFare")?.toDoubleOrNull() ?: 0.0
            val defaultFare = backStack.arguments?.getString("defaultFare")?.toDoubleOrNull() ?: 0.0

            val vm = remember {
                WaitingForRiderViewModel(repository, tripId, offeredFare, defaultFare)
            }

            WaitingForRiderScreen(
                viewModel       = vm,
                onTripAgreed    = { id ->
                    navController.navigate(Screen.ActiveTrip.route(id)) {
                        popUpTo(Screen.WaitingForRider.route) { inclusive = true }
                    }
                },
                onTripRejected  = { message ->
                    navController.navigate(Screen.IncomingTrips.route) {
                        popUpTo(Screen.IncomingTrips.route) { inclusive = false }
                    }
                    navController.currentBackStackEntry
                        ?.savedStateHandle?.set("snack", message)
                },
                onTripCancelled = { message ->
                    navController.navigate(Screen.IncomingTrips.route) {
                        popUpTo(Screen.IncomingTrips.route) { inclusive = false }
                    }
                    navController.currentBackStackEntry
                        ?.savedStateHandle?.set("snack", message)
                },
                // Use the real admin floor (defaultFare) and the accurate counter
                // count from the Realtime payload — not the driver's offered price.
                onRiderCountered = { id, riderFare, driverCounterCount, realDefaultFare ->
                    navController.navigate(
                        Screen.CounterDecision.route(
                            tripId             = id,
                            riderFare          = riderFare,
                            defaultFare        = realDefaultFare,
                            driverCounterCount = driverCounterCount,
                            maxDriverCounters  = 3
                        )
                    ) {
                        popUpTo(Screen.WaitingForRider.route) { inclusive = true }
                    }
                }
            )
        }

        // ── Counter decision ──────────────────────────────────────────────────

        composable(
            route     = Screen.CounterDecision.route,
            arguments = listOf(
                navArgument("tripId")             { type = NavType.StringType },
                navArgument("riderFare")          { type = NavType.StringType },
                navArgument("defaultFare")        { type = NavType.StringType },
                navArgument("driverCounterCount") { type = NavType.IntType },
                navArgument("maxDriverCounters")  { type = NavType.IntType }
            )
        ) { backStack ->
            val tripId             = backStack.arguments?.getString("tripId")                        ?: return@composable
            val riderFare          = backStack.arguments?.getString("riderFare")?.toDoubleOrNull()   ?: 0.0
            val defaultFare        = backStack.arguments?.getString("defaultFare")?.toDoubleOrNull() ?: 0.0
            val driverCounterCount = backStack.arguments?.getInt("driverCounterCount")               ?: 0
            val maxDriverCounters  = backStack.arguments?.getInt("maxDriverCounters")                ?: 3

            val vm = remember {
                CounterDecisionViewModel(
                    repository, tripId, riderFare, defaultFare,
                    driverCounterCount, maxDriverCounters
                )
            }
            CounterDecisionScreen(
                viewModel   = vm,
                onAccepted  = { id ->
                    // Driver matched rider's price — go back to WaitingForRider
                    // with riderFare as the new offeredFare, keeping defaultFare intact.
                    navController.navigate(
                        Screen.WaitingForRider.route(id, riderFare, defaultFare)
                    ) {
                        popUpTo(Screen.CounterDecision.route) { inclusive = true }
                    }
                },
                onCountered = { id, newFare ->
                    navController.navigate(
                        Screen.WaitingForRider.route(id, newFare, defaultFare)
                    ) {
                        popUpTo(Screen.CounterDecision.route) { inclusive = true }
                    }
                },
                onRejected  = { message ->
                    navController.navigate(Screen.IncomingTrips.route) {
                        popUpTo(Screen.IncomingTrips.route) { inclusive = false }
                    }
                    navController.currentBackStackEntry
                        ?.savedStateHandle?.set("snack", message)
                }
            )
        }

        // ── Active trip ───────────────────────────────────────────────────────

        composable(
            route     = Screen.ActiveTrip.route,
            arguments = listOf(navArgument("tripId") { type = NavType.StringType })
        ) { backStack ->
            val tripId = backStack.arguments?.getString("tripId") ?: return@composable
            val vm     = remember { ActiveTripViewModel(repository, tripId) }
            ActiveTripScreen(
                viewModel       = vm,
                onTripCompleted = { id ->
                    navController.navigate(Screen.RateRider.route(id)) {
                        popUpTo(Screen.ActiveTrip.route) { inclusive = true }
                    }
                },
                onTripCancelled = {
                    navController.navigate(Screen.Map.route) {
                        popUpTo(Screen.Map.route) { inclusive = false }
                    }
                }
            )
        }

        // ── Rate rider ────────────────────────────────────────────────────────

        composable(
            route     = Screen.RateRider.route,
            arguments = listOf(navArgument("tripId") { type = NavType.StringType })
        ) { backStack ->
            val tripId = backStack.arguments?.getString("tripId") ?: return@composable
            val vm     = remember { RateRiderViewModel(repository, tripId) }
            RateRiderScreen(
                viewModel = vm,
                onDone    = {
                    navController.navigate(Screen.Map.route) {
                        popUpTo(Screen.Map.route) { inclusive = false }
                    }
                }
            )
        }

        // ── Trip history ──────────────────────────────────────────────────────

        composable(Screen.TripHistory.route) {
            val vm = remember { TripHistoryViewModel(repository) }
            TripHistoryScreen(
                viewModel  = vm,
                onBack     = { navController.popBackStack() },
                onRateTrip = { tripId -> navController.navigate(Screen.RateRider.route(tripId)) }
            )
        }
    }
}