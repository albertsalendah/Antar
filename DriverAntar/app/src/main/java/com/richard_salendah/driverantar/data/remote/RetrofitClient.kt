package com.richard_salendah.driverantar.data.remote

import android.content.Context
import android.content.Intent
import android.util.Log
import com.richard_salendah.driverantar.BuildConfig
import com.richard_salendah.driverantar.data.model.RefreshRequest
import com.richard_salendah.driverantar.utils.SessionManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {

    // ── Logging interceptor ───────────────────────────────────────────────────

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    // ── Auth interceptor ──────────────────────────────────────────────────────

    /**
     * Intercepts every 401 response and attempts a silent token refresh.
     *
     * TOKEN-RACE fix: replaced `@Volatile isRefreshing` boolean with a [Mutex].
     * The old approach let a second simultaneous 401 slip through and fail
     * immediately because `isRefreshing` was already true.
     *
     * New behaviour:
     *   1. First 401 acquires the lock and refreshes.
     *   2. Second simultaneous 401 waits on the lock.
     *   3. Once the lock is released, the second request detects the token
     *      already changed (compares raw token before/after lock) and retries
     *      with the new token — no second refresh attempt needed.
     */
    private class AuthInterceptor(private val context: Context) : okhttp3.Interceptor {

        private val refreshMutex = Mutex()

        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            val originalRequest = chain.request()
            val response = chain.proceed(originalRequest)

            // Only handle 401 when we have a refresh token to use
            if (response.code != 401 || SessionManager.refreshToken.isBlank()) {
                return response
            }

            // Skip auth endpoints to avoid refresh loops
            val path = originalRequest.url.encodedPath
            if (path.contains("/refresh") || path.contains("/login") || path.contains("/register")) {
                return response
            }

            Log.d("AuthInterceptor", "401 received — attempting token refresh")
            response.close()

            // Capture the current token BEFORE acquiring the lock so we can detect
            // whether a concurrent thread already performed the refresh while we waited.
            val tokenBeforeLock = SessionManager.rawAccessToken

            return runBlocking {
                refreshMutex.withLock {
                    if (SessionManager.rawAccessToken != tokenBeforeLock) {
                        // Token changed while we waited — another thread already refreshed.
                        // Just retry with the new token; no second refresh needed.
                        Log.d("AuthInterceptor", "Token already refreshed concurrently — retrying")
                        chain.proceed(
                            originalRequest.newBuilder()
                                .header("Authorization", SessionManager.token)
                                .build()
                        )
                    } else {
                        try {
                            val refreshed = refreshAccessToken()
                            if (refreshed) {
                                Log.d("AuthInterceptor", "Token refreshed — retrying request")
                                chain.proceed(
                                    originalRequest.newBuilder()
                                        .header("Authorization", SessionManager.token)
                                        .build()
                                )
                            } else {
                                Log.w("AuthInterceptor", "Refresh failed — logging out")
                                triggerLogout()
                                chain.proceed(originalRequest)
                            }
                        } catch (e: Exception) {
                            Log.e("AuthInterceptor", "Error during token refresh", e)
                            triggerLogout()
                            chain.proceed(originalRequest)
                        }
                    }
                }
            }
        }

        private suspend fun refreshAccessToken(): Boolean {
            return try {
                // Use a separate bare client to avoid triggering this interceptor recursively
                val refreshApi = Retrofit.Builder()
                    .baseUrl(BuildConfig.BASE_URL)
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(OkHttpClient.Builder().build())
                    .build()
                    .create(DriverApiService::class.java)

                val response = refreshApi.refreshToken(
                    RefreshRequest(SessionManager.refreshToken)
                )

                if (response.isSuccessful) {
                    val data = response.body()?.data
                    if (data != null) {
                        SessionManager.save(
                            accessToken      = data.access_token,
                            refreshToken     = data.refresh_token,
                            driverId         = data.driver_id,
                            fullName         = data.full_name,
                            expiresInSeconds = 3600L
                        )
                        true
                    } else false
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.e("AuthInterceptor", "refreshAccessToken error", e)
                false
            }
        }

        private fun triggerLogout() {
            SessionManager.clear()
            val intent = Intent(ACTION_SESSION_EXPIRED).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }
    }

    // ── Public constants ──────────────────────────────────────────────────────

    const val ACTION_SESSION_EXPIRED = "com.richard_salendah.driverantar.SESSION_EXPIRED"

    // ── Singleton instance ────────────────────────────────────────────────────

    private lateinit var appContext: Context

    fun initWith(context: Context) {
        appContext = context.applicationContext
    }

    val instance: DriverApiService by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .addInterceptor(AuthInterceptor(appContext))
            .build()

        Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
            .create(DriverApiService::class.java)
    }
}