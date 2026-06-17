package com.richard_salendah.driverantar

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.richard_salendah.driverantar.data.remote.DriverRepository
import com.richard_salendah.driverantar.data.remote.RetrofitClient
import com.richard_salendah.driverantar.ui.navigation.AppNavGraph
import com.richard_salendah.driverantar.ui.navigation.Screen
import com.richard_salendah.driverantar.ui.service.LocationService
import com.richard_salendah.driverantar.utils.ConnectivityObserver
import com.richard_salendah.driverantar.utils.SessionManager
import org.osmdroid.config.Configuration
import com.richard_salendah.driverantar.ui.navigation.DeepLinkHandler

/**
 * Single Activity — all screens are Compose destinations in AppNavGraph.
 *
 * Responsibilities:
 *   1. Initialise SessionManager + RetrofitClient with applicationContext.
 *   2. Initialise OSMDroid tile cache.
 *   3. Request location + notification permissions.
 *   4. Handle FCM deep-link intents (new_trip → IncomingTrips, offer_accepted → ActiveTrip).
 *   5. Listen for SESSION_EXPIRED broadcast from AuthInterceptor and navigate to login.
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_NAVIGATE_TO = "navigate_to"
    }

    // ── Permission launchers ──────────────────────────────────────────────────

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result handled in onResume via MapViewModel.checkGps() */ }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best-effort — app works without it */ }

    // ── Session expired broadcast ─────────────────────────────────────────────

    private val sessionExpiredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            stopService(Intent(context, LocationService::class.java))
            SessionManager.clear()
            val restart = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("session_expired", true)
            }
            startActivity(restart)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── MUST be first — everything below depends on these two ─────────────
        // SessionManager reads/writes SharedPreferences and needs a Context.
        // RetrofitClient.instance is a lazy delegate — if it evaluates before
        // initWith() is called, appContext is uninitialised → crash.
        // Both must be called before setContent{} composes any screen that
        // accesses SessionManager.token or RetrofitClient.instance.
        SessionManager.init(applicationContext)
        RetrofitClient.initWith(applicationContext)
        ConnectivityObserver.init(applicationContext)
        // ─────────────────────────────────────────────────────────────────────

        // OSMDroid MUST be configured before any MapView is created
        Configuration.getInstance().apply {
            load(applicationContext, PreferenceManager.getDefaultSharedPreferences(applicationContext))
            userAgentValue = packageName
        }

        val sessionExpired = intent.getBooleanExtra("session_expired", false)
        intent.getStringExtra(EXTRA_NAVIGATE_TO)?.let { DeepLinkHandler.emit(it) }

        setContent {
            MaterialTheme {
                val navController   = rememberNavController()

                LaunchedEffect(sessionExpired) {
                    if (sessionExpired) {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }

                AppNavGraph(
                    navController = navController,
                    repository    = DriverRepository(RetrofitClient.instance),
                )
            }
        }

        registerSessionReceiver()
        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        checkLocationPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // App backgrounded (not killed) — onCreate's deepLinkRoute path won't
        // fire again, so route via DeepLinkHandler instead (cold-start unaffected).
        intent.getStringExtra(EXTRA_NAVIGATE_TO)?.let { DeepLinkHandler.emit(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(sessionExpiredReceiver)
        ConnectivityObserver.destroy()
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun requestPermissions() {
        checkLocationPermission()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun checkLocationPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED -> {}
            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ->
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            else ->
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // ── Session expired broadcast ─────────────────────────────────────────────

    private fun registerSessionReceiver() {
        val filter = IntentFilter(RetrofitClient.ACTION_SESSION_EXPIRED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(sessionExpiredReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(sessionExpiredReceiver, filter)
        }
    }
}