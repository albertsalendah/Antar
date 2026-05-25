package com.richard_salendah.antar.data.remote

import com.richard_salendah.antar.BuildConfig
import com.richard_salendah.antar.data.local.SessionManager
import com.richard_salendah.antar.data.model.FcmTokenRequest
import kotlinx.coroutines.runBlocking
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
 */
private class TokenAuthenticator(
    private val sessionManager: SessionManager,
) : Authenticator {

    @Volatile private var isRefreshing = false

    override fun authenticate(route: Route?, response: Response): Request? {
        if (isRefreshing) return null
        isRefreshing = true

        return try {
            val refreshToken = runBlocking { sessionManager.getRefreshToken() }
                ?: return null

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
                runBlocking { sessionManager.clearSession() }
                return null
            }

            val json = JSONObject(refreshResponse.body.string() ?: "{}")
            val data = json.optJSONObject("data") ?: return null
            val newAccess  = data.optString("access_token").takeIf { it.isNotEmpty() } ?: return null
            val newRefresh = data.optString("refresh_token").takeIf { it.isNotEmpty() } ?: return null
            val riderId    = data.optString("rider_id", "")
            val fullName   = data.optString("full_name", "")

            runBlocking {
                sessionManager.saveSession(newAccess, newRefresh, riderId, fullName)
            }

            response.request.newBuilder()
                .header("Authorization", "Bearer $newAccess")
                .build()
        } catch (e: Exception) {
            runBlocking { sessionManager.clearSession() }
            null
        } finally {
            isRefreshing = false
        }
    }
}