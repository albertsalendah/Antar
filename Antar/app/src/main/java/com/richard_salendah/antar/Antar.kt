package com.richard_salendah.antar

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import com.richard_salendah.antar.data.local.SessionManager
import com.richard_salendah.antar.data.remote.ApiClient
import com.richard_salendah.antar.data.remote.ApiService
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import org.conscrypt.Conscrypt
import java.security.Security

class Antar : Application() {

    lateinit var sessionManager: SessionManager private set
    lateinit var apiService: ApiService         private set
    lateinit var supabase: SupabaseClient       private set

    // Tracked by ActivityLifecycleCallbacks — used by FCM service to decide
    // whether to route silently in-app or post a system notification.
    var isForeground: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
        sessionManager = SessionManager(applicationContext)
        apiService      = ApiClient.build(sessionManager)
        supabase        = createSupabaseClient(
            supabaseUrl  = BuildConfig.SUPABASE_URL,
            supabaseKey  = BuildConfig.SUPABASE_ANON_KEY,
        ) { install(Realtime) }

        createNotificationChannel()
        registerForegroundTracker()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "antar_rider",
            "Antar",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "Trip offers and status updates" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun registerForegroundTracker() {
        var started = 0
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity)  { if (++started == 1) isForeground = true }
            override fun onActivityStopped(a: Activity)  { if (--started == 0) isForeground = false }
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityResumed(a: Activity)  = Unit
            override fun onActivityPaused(a: Activity)   = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
    }
}