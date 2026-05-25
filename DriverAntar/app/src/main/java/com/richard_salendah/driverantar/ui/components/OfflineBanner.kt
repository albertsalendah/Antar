package com.richard_salendah.driverantar.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Animated banner shown at the top of any screen when the device is offline.
 * Slides in/out smoothly so it doesn't feel jarring.
 *
 * Usage:
 *   val isOnline by ConnectivityObserver.isOnline.collectAsState()
 *   OfflineBanner(visible = !isOnline)
 */
@Composable
fun OfflineBanner(
    visible:  Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible  = visible,
        enter    = expandVertically() + fadeIn(),
        exit     = shrinkVertically() + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            color    = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier          = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.WifiOff,
                    contentDescription = null,
                    tint     = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Tidak ada koneksi internet — aksi tidak tersedia",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}