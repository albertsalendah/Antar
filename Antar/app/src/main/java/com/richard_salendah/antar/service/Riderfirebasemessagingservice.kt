package com.richard_salendah.antar.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.richard_salendah.antar.Antar
import com.richard_salendah.antar.MainActivity
import com.richard_salendah.antar.R
import com.richard_salendah.antar.data.model.FcmTokenRequest
import com.richard_salendah.antar.navigation.DeepLinkEvent
import com.richard_salendah.antar.navigation.DeepLinkHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RiderFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                (application as Antar).apiService.saveFcmToken(FcmTokenRequest(token))
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val type   = message.data["type"]    ?: return
        val tripId = message.data["trip_id"] ?: ""
        val title  = message.notification?.title ?: titleFor(type)
        val body   = message.notification?.body  ?: ""

        if (isAppInForeground()) {
            routeInApp(type, tripId)
            return
        }

        showNotification(title, body, type, tripId)
    }

    // ── Foreground routing ────────────────────────────────────────────────────

    private fun routeInApp(type: String, tripId: String) {
        val event: DeepLinkEvent? = when (type) {
            "driver_offer",
            "driver_counter" -> if (tripId.isNotEmpty()) DeepLinkEvent.ToNegotiation(tripId) else null
            "offer_accepted",
            "driver_arrived" -> if (tripId.isNotEmpty()) DeepLinkEvent.ToActiveTrip(tripId) else null
            // candidate_declined and driver_withdrew: when the app is foreground the
            // ViewModel already owns the popup via its Realtime/polling subscription.
            // Emitting here would cause a double-trigger. Background-only: handled
            // via showNotification() → MainActivity.handleFcmIntent() → DeepLinkHandler.
            // [R1, R2] — no foreground emit for these two types.
            "candidate_declined",
            "driver_withdrew" -> null
            else -> null
        }
        event?.let { DeepLinkHandler.emit(it) }
    }

    // ── Background notification ───────────────────────────────────────────────

    private fun showNotification(title: String, body: String, type: String, tripId: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_FCM_TYPE,    type)
            putExtra(EXTRA_FCM_TRIP_ID, tripId)
        }
        val pending = PendingIntent.getActivity(
            this, tripId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, "antar_rider")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(tripId.hashCode(), notification)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isAppInForeground(): Boolean =
        (application as Antar).isForeground

    private fun titleFor(type: String) = when (type) {
        "driver_offer"       -> "Ada Penawaran Harga!"
        "driver_counter"     -> "Driver Balik Menawar!"
        "offer_accepted"     -> "Penawaran Diterima!"
        "driver_arrived"     -> "Driver Sudah Tiba!"
        "candidate_declined" -> "Driver Menolak"
        "driver_withdrew"    -> "Driver Menarik Penawaran"
        else                 -> "Antar"
    }

    companion object {
        const val EXTRA_FCM_TYPE    = "fcm_type"
        const val EXTRA_FCM_TRIP_ID = "fcm_trip_id"
    }
}
