package com.richard_salendah.driverantar.data.remote

import android.content.Context
import android.content.Intent
import android.util.Log
import com.richard_salendah.driverantar.BuildConfig
import com.richard_salendah.driverantar.data.model.RefreshRequest
import com.richard_salendah.driverantar.utils.SessionManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {

    // ── Logging interceptor ───────────────────────────────────────────────────

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    // ── Auth interceptor ──────────────────────────────────────────────────────

    /**
     * Intercepts every response. When a 401 is received:
     *   1. Checks if we have a refresh token.
     *   2. Calls POST /api/v1/driver/refresh synchronously.
     *   3. If refresh succeeds → saves new tokens and retries the original request.
     *   4. If refresh fails (refresh token expired/invalid) → clears session and
     *      broadcasts a logout intent so the app navigates to the login screen.
     *
     * This runs transparently — ViewModels and Repository never see the 401.
     *
     * Note: we use runBlocking because OkHttp interceptors are synchronous.
     * This is acceptable here since we're already on a background thread.
     */
    private class AuthInterceptor(private val context: Context) : okhttp3.Interceptor {

        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            val originalRequest = chain.request()
            val response = chain.proceed(originalRequest)

            // Only attempt refresh on 401 and only when we have a refresh token
            if (response.code != 401 || SessionManager.refreshToken.isBlank()) {
                return response
            }

            // Skip refresh for auth endpoints themselves (avoid infinite loop)
            val path = originalRequest.url.encodedPath
            if (path.contains("/refresh") || path.contains("/login") || path.contains("/register")) {
                return response
            }

            Log.d("AuthInterceptor", "401 received — attempting token refresh")
            response.close()

            return runBlocking {
                try {
                    val refreshed = refreshAccessToken()
                    if (refreshed) {
                        Log.d("AuthInterceptor", "Token refreshed — retrying request")
                        // Retry original request with new token
                        val newRequest = originalRequest.newBuilder()
                            .header("Authorization", SessionManager.token)
                            .build()
                        chain.proceed(newRequest)
                    } else {
                        Log.w("AuthInterceptor", "Refresh failed — logging out")
                        triggerLogout()
                        // Return an empty 401 so the caller knows something went wrong
                        chain.proceed(originalRequest)
                    }
                } catch (e: Exception) {
                    Log.e("AuthInterceptor", "Error during token refresh", e)
                    triggerLogout()
                    chain.proceed(originalRequest)
                }
            }
        }

        private suspend fun refreshAccessToken(): Boolean {
            return try {
                // Direct Retrofit call — uses a separate client without this interceptor
                // to avoid recursion
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
                            accessToken = data.access_token,
                            refreshToken = data.refresh_token,
                            driverId = data.driver_id,
                            fullName = data.full_name,
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
            // Broadcast a logout event. MainActivity / any active activity listens
            // for this intent and navigates to AuthActivity.
            val intent = Intent(ACTION_SESSION_EXPIRED).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }
    }

    // ── Public constants ──────────────────────────────────────────────────────

    /** Broadcast action sent when the refresh token has expired */
    const val ACTION_SESSION_EXPIRED = "com.richard_salendah.driverantar.SESSION_EXPIRED"

    // ── Singleton instance ────────────────────────────────────────────────────

    // Initialized lazily with application context — call initWith(context) once
    // in DriverApplication.onCreate()
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