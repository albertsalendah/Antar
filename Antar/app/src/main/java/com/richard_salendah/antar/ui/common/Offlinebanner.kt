package com.richard_salendah.antar.ui.common

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ── Connectivity state ────────────────────────────────────────────────────────

@Composable
fun rememberConnectivityState(): State<Boolean> {
    val context = LocalContext.current
    val cm      = context.getSystemService(ConnectivityManager::class.java)

    val isOnline = remember {
        mutableStateOf(
            cm.activeNetwork?.let { n ->
                cm.getNetworkCapabilities(n)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            } ?: false
        )
    }

    DisposableEffect(Unit) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { isOnline.value = true }
            override fun onLost(network: Network)      { isOnline.value = false }
            override fun onUnavailable()               { isOnline.value = false }
        }
        cm.registerDefaultNetworkCallback(callback)
        onDispose { cm.unregisterNetworkCallback(callback) }
    }

    return isOnline
}

// ── Banner ────────────────────────────────────────────────────────────────────

/**
 * Drop-in banner — place it just below the top bar of any screen.
 * Animates in/out automatically based on connectivity.
 */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    val isOnline by rememberConnectivityState()

    AnimatedVisibility(
        visible = !isOnline,
        enter   = expandVertically(),
        exit    = shrinkVertically(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF37474F))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.WifiOff,
                contentDescription = null,
                tint     = Color.White,
                modifier = Modifier.size(16.dp),
            )
            Text(
                "Tidak ada koneksi internet",
                style = MaterialTheme.typography.bodySmall.copy(
                    color      = Color.White,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}