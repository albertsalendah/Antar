package com.richard_salendah.antar

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.compose.rememberNavController
import com.richard_salendah.antar.navigation.AntarNavGraph
import com.richard_salendah.antar.navigation.DeepLinkEvent
import com.richard_salendah.antar.navigation.DeepLinkHandler
import com.richard_salendah.antar.navigation.Screen
import com.richard_salendah.antar.service.RiderFirebaseMessagingService.Companion.EXTRA_FCM_TRIP_ID
import com.richard_salendah.antar.service.RiderFirebaseMessagingService.Companion.EXTRA_FCM_TYPE
import com.richard_salendah.antar.ui.theme.AntarTheme
import kotlinx.coroutines.launch


class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Route FCM tap that cold-started the app
        handleFcmIntent(intent)

        val sessionManager = (application as Antar).sessionManager

        setContent {
            AntarTheme {
                val navController        = rememberNavController()
                val scope                = rememberCoroutineScope()
                val startDestination     = remember { mutableStateOf<String?>(null) }

                scope.launch {
                    startDestination.value = if (sessionManager.isLoggedIn())
                        Screen.Home.route
                    else
                        Screen.Login.route
                }

                startDestination.value?.let { start ->
                    AntarNavGraph(
                        navController    = navController,
                        startDestination = start,
                    )
                }
            }
        }
    }

    // Called when app is already running and user taps a notification
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleFcmIntent(intent)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun handleFcmIntent(intent: Intent?) {
        val type   = intent?.getStringExtra(EXTRA_FCM_TYPE)    ?: return
        val tripId = intent.getStringExtra(EXTRA_FCM_TRIP_ID)  ?: return
        if (tripId.isEmpty()) return

        val event = when (type) {
            "driver_offer",
            "driver_counter" -> DeepLinkEvent.ToNegotiation(tripId)
            "offer_accepted" -> DeepLinkEvent.ToActiveTrip(tripId)
            "candidate_declined" -> DeepLinkEvent.ToCandidateReview(tripId)
            else             -> null
        }
        event?.let { DeepLinkHandler.emit(it) }
    }
}