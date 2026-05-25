package com.richard_salendah.driverantar.ui.supabase

import com.richard_salendah.driverantar.BuildConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.realtime

/**
 * Singleton Supabase client used exclusively for Realtime subscriptions.
 *
 * Auth is handled by the Go server + Supabase REST — we don't use the
 * Supabase Kotlin SDK for auth, only for:
 *   1. Subscribing to trips row changes (WaitingForRider screen)
 *   2. No other use currently
 *
 * SUPABASE_URL and SUPABASE_ANON_KEY are injected at build time from
 * local.properties via BuildConfig — same values as in .env on the server.
 */
object SupabaseClientHolder {

    val realtime get() = client.realtime
    val client by lazy {
        createSupabaseClient(
            supabaseUrl  = BuildConfig.SUPABASE_URL,
            supabaseKey  = BuildConfig.SUPABASE_ANON_KEY
        ) {
            install(Realtime)
        }
    }
}