package com.richard_salendah.driverantar.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observes real-time network connectivity via ConnectivityManager.NetworkCallback.
 *
 * Follows the same pattern as SessionManager — call init(context) once in
 * MainActivity.onCreate(), call destroy() in MainActivity.onDestroy().
 * Then collect [isOnline] from any Composable with .collectAsState().
 */
class ConnectivityObserver private constructor(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val _isOnline = MutableStateFlow(checkCurrentState())

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _isOnline.value = true
        }
        override fun onLost(network: Network) {
            // Another network may still be active — re-check rather than blindly going offline
            _isOnline.value = cm.activeNetwork != null
        }
        override fun onUnavailable() {
            _isOnline.value = false
        }
    }

    init { cm.registerDefaultNetworkCallback(callback) }

    fun unregister() {
        try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) { }
    }

    private fun checkCurrentState(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps    = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        private lateinit var _instance: ConnectivityObserver

        /** Collect this in any Composable: `val isOnline by ConnectivityObserver.isOnline.collectAsState()` */
        val isOnline: StateFlow<Boolean> get() = _instance._isOnline

        fun init(context: Context) {
            _instance = ConnectivityObserver(context.applicationContext)
        }

        fun destroy() {
            if (::_instance.isInitialized) _instance.unregister()
        }
    }
}