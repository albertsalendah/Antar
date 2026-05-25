package com.richard_salendah.driverantar.ui.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.richard_salendah.driverantar.MainActivity
import com.richard_salendah.driverantar.R
import com.richard_salendah.driverantar.data.remote.DriverRepository
import com.richard_salendah.driverantar.data.remote.RetrofitClient
import com.richard_salendah.driverantar.ui.navigation.Screen
import com.richard_salendah.driverantar.utils.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives FCM data payloads from the server.
 *
 * Two payload types (set in data map, not notification block):
 *
 *   new_trip       — a nearby trip was requested that matches the driver's vehicle type.
 *                    data["type"]    = "new_trip"
 *                    data["trip_id"] = "<uuid>"
 *                    Action: show heads-up notification → tap navigates to IncomingTrips
 *
 *   offer_accepted — the rider accepted the driver's price offer.
 *                    data["type"]    = "offer_accepted"
 *                    data["trip_id"] = "<uuid>"
 *                    Action: show heads-up notification → tap navigates to ActiveTrip
 *
 * Both payloads arrive as data-only messages so they work whether the app is
 * in the foreground, background, or killed. The notification is built here
 * rather than relying on the FCM display message (which would bypass this handler
 * when the app is in the background on Android).
 */
class AntarDriverMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── New token ─────────────────────────────────────────────────────────────

    /**
     * Called when FCM rotates the device token.
     * MUST re-register with the server immediately — without this, the driver
     * stops receiving new_trip notifications after a token rotation.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM token rotated — re-registering with server")
        if (SessionManager.isLoggedIn) {
            serviceScope.launch {
                DriverRepository(RetrofitClient.instance)
                    .saveFcmToken(SessionManager.token, token)
                    .onFailure { Log.e(TAG, "Failed to save rotated FCM token", it) }
            }
        }
    }

    // ── Message received ──────────────────────────────────────────────────────

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val data   = message.data
        val type   = data["type"]   ?: return
        val tripId = data["trip_id"] ?: return

        Log.d(TAG, "FCM received type=$type trip_id=$tripId")

        when (type) {
            "new_trip" -> showNewTripNotification(tripId)
            "offer_accepted" -> showOfferAcceptedNotification(tripId)
            else -> Log.w(TAG, "Unknown FCM type: $type")
        }
    }

    // ── Notification builders ─────────────────────────────────────────────────

    private fun showNewTripNotification(tripId: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_NAVIGATE_TO, Screen.IncomingTrips.route)
        }
        showNotification(
            notificationId = NOTIF_ID_NEW_TRIP,
            channelId      = CHANNEL_TRIPS,
            title          = "Ada Permintaan Baru! 🛵",
            body           = "Penumpang mencari driver di dekat Anda",
            intent         = intent
        )
    }

    private fun showOfferAcceptedNotification(tripId: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_NAVIGATE_TO, Screen.ActiveTrip.route(tripId))
        }
        showNotification(
            notificationId = NOTIF_ID_OFFER_ACCEPTED,
            channelId      = CHANNEL_TRIPS,
            title          = "Penawaran Diterima! ✅",
            body           = "Penumpang menerima harga Anda — segera berangkat",
            intent         = intent
        )
    }

    private fun showNotification(
        notificationId: Int,
        channelId: String,
        title: String,
        body: String,
        intent: Intent
    ) {
        createChannelIfNeeded(channelId)

        val pendingIntent = PendingIntent.getActivity(
            this, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_car)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(this).notify(notificationId, notification)
        }
    }

    private fun createChannelIfNeeded(channelId: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Trip Notifications",
                    NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    companion object {
        private const val TAG               = "FCMService"
        private const val CHANNEL_TRIPS     = "trips"
        private const val NOTIF_ID_NEW_TRIP = 100
        private const val NOTIF_ID_OFFER_ACCEPTED = 101
    }
}