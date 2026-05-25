package com.richard_salendah.antar.ui.common

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

enum class HapticType {
    /** Short tick — confirm, select */
    Tick,
    /** Medium buzz — action submitted, trip started */
    Confirm,
    /** Double bump — error */
    Error,
}

class HapticFeedback(private val context: Context) {

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(VibratorManager::class.java))?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }

    fun perform(type: HapticType) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        when (type) {
            HapticType.Tick -> vib.vibrate(
                VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
            )
            HapticType.Confirm -> vib.vibrate(
                VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
            )
            HapticType.Error -> vib.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 50, 60, 50), -1)
            )
        }
    }
}

@Composable
fun rememberHaptic(): HapticFeedback {
    val context = LocalContext.current
    return remember(context) { HapticFeedback(context) }
}