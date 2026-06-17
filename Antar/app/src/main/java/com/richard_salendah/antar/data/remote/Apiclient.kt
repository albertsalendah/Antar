package com.richard_salendah.antar.data.remote

import com.richard_salendah.antar.BuildConfig
import com.richard_salendah.antar.data.local.SessionManager
import com.richard_salendah.antar.data.model.FcmTokenRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    /**
     * Builds the Retrofit instance with:
     *   - AuthInterceptor: attaches Bearer token to every request
     *   - TokenAuthenticator: on 401, silently refreshes tokens and retries once
     *   - Logging: full body in debug builds only
     */
    fun build(sessionManager: SessionManager): ApiService {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG)
                HttpLoggingInterceptor.Level.BODY
            else
                HttpLoggingInterceptor.Level.NONE
        }

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val token = runBlocking { sessionManager.getAccessToken() }
                val request = if (token != null) {
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                } else {
                    chain.request()
                }
                chain.proceed(request)
            }
            .authenticator(TokenAuthenticator(sessionManager))
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}

/**
 * On a 401 response, refreshes the access token once then retries.
 * If refresh also fails (expired refresh token), clears session so the
 * app redirects to login on the next navigation attempt.
 *
 * TOKEN-RACE fix: replaced `@Volatile isRefreshing` with a [Mutex]. The old
 * approach let a second simultaneous 401 slip through and fail immediately
 * because `isRefreshing` was already true.
 *
 * New behaviour:
 *   1. First 401 acquires the lock and refreshes.
 *   2. Second simultaneous 401 waits on the lock.
 *   3. Once the lock is released, the second request detects the token
 *      already changed (compares raw token before/after lock) and retries
 *      with the new token — no second refresh attempt needed.
 */
private class TokenAuthenticator(
    private val sessionManager: SessionManager,
) : Authenticator {

    private val refreshMutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? = runBlocking {
        val refreshToken = sessionManager.getRefreshToken() ?: return@runBlocking null

        // Capture the current access token BEFORE acquiring the lock so we can
        // detect whether another coroutine already performed the refresh while
        // we waited.
        val tokenBeforeLock = sessionManager.getAccessToken()

        refreshMutex.withLock {
            val currentToken = sessionManager.getAccessToken()
            if (currentToken != null && currentToken != tokenBeforeLock) {
                // Token already refreshed concurrently — just retry with the new token.
                response.request.newBuilder()
                    .header("Authorization", "Bearer $currentToken")
                    .build()
            } else {
                try {
                    val tempClient = OkHttpClient()
                    val body =
                        """{"refresh_token":"$refreshToken"}"""
                            .toRequestBody("application/json".toMediaTypeOrNull())
                    val refreshRequest = Request.Builder()
                        .url(BuildConfig.BASE_URL.trimEnd('/') + "/api/v1/rider/refresh")
                        .post(body)
                        .build()

                    val refreshResponse = tempClient.newCall(refreshRequest).execute()
                    if (!refreshResponse.isSuccessful) {
                        sessionManager.clearSession()
                        return@withLock null
                    }

                    val json = JSONObject(refreshResponse.body.string() ?: "{}")
                    val data = json.optJSONObject("data") ?: return@withLock null
                    val newAccess  = data.optString("access_token").takeIf { it.isNotEmpty() } ?: return@withLock null
                    val newRefresh = data.optString("refresh_token").takeIf { it.isNotEmpty() } ?: return@withLock null
                    val riderId    = data.optString("rider_id", "")
                    val fullName   = data.optString("full_name", "")

                    sessionManager.saveSession(newAccess, newRefresh, riderId, fullName)

                    response.request.newBuilder()
                        .header("Authorization", "Bearer $newAccess")
                        .build()
                } catch (e: Exception) {
                    sessionManager.clearSession()
                    null
                }
            }
        }
    }
}