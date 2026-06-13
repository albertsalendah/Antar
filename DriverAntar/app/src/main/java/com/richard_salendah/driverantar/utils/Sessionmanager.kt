package com.richard_salendah.driverantar.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

/**
 * Persists the driver session across app restarts.
 *
 * Token lifetime (Supabase defaults):
 *   Access token  — 1 hour.   Used for all API calls.
 *   Refresh token — 60 days.  Used to get a new access token when it expires.
 *
 * Storage: EncryptedSharedPreferences backed by AES256-GCM (values) and
 * AES256-SIV (keys). The master key is stored in the Android Keystore —
 * never on disk in plain text. This satisfies the Phase 4 security requirement.
 *
 * Migration note: EncryptedSharedPreferences cannot read an existing plain
 * SharedPreferences file. Any previous session is silently lost on first run
 * after this upgrade, which forces a one-time re-login. This is intentional
 * and safe — refresh tokens are short-lived and the server validates them.
 */
object SessionManager {

    private const val PREF_NAME = "antar_session_enc"   // renamed to avoid reading old plain file
    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_DRIVER_ID = "driver_id"
    private const val KEY_FULL_NAME = "full_name"
    private const val KEY_EXPIRES_AT = "token_expires_at"

    private lateinit var prefs: SharedPreferences

    /**
     * KEYSTORE-CRASH fix: if the Keystore-backed master key was invalidated
     * (e.g. lock screen type changed, first unlock on a new account), creating
     * EncryptedSharedPreferences throws GeneralSecurityException and would
     * otherwise crash on every cold start. Recover by wiping the stale prefs
     * file + Keystore alias and recreating with a fresh key — forces a
     * one-time re-login, per the migration note above.
     */
    fun init(context: Context) {
        prefs = try {
            createEncryptedPrefs(context)
        } catch (e: Exception) {
            context.deleteSharedPreferences(PREF_NAME)
            try {
                KeyStore.getInstance("AndroidKeyStore").apply {
                    load(null)
                    deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                }
            } catch (_: Exception) { /* alias may not exist — ignore */ }
            createEncryptedPrefs(context)
        }
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Saves the session after a successful login or token refresh.
     * [expiresInSeconds] is the lifetime of the access token (Supabase = 3600s).
     * We store the absolute expiry time so we can check it without network calls.
     */
    fun save(
        accessToken: String,
        refreshToken: String,
        driverId: String,
        fullName: String,
        expiresInSeconds: Long = 3600L
    ) {
        val expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000L)
        prefs.edit()
            .putString(KEY_ACCESS, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .putString(KEY_DRIVER_ID, driverId)
            .putString(KEY_FULL_NAME, fullName)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()

    /** "Bearer <access_token>" — pass as Authorization header */
    val token: String get() = "Bearer ${prefs.getString(KEY_ACCESS, "") ?: ""}"

    /** Raw access token without "Bearer " prefix */
    val rawAccessToken: String get() = prefs.getString(KEY_ACCESS, "") ?: ""

    /** Raw refresh token — used by AuthInterceptor to get a new access token */
    val refreshToken: String get() = prefs.getString(KEY_REFRESH, "") ?: ""

    val driverId: String get() = prefs.getString(KEY_DRIVER_ID, "") ?: ""
    val fullName: String get() = prefs.getString(KEY_FULL_NAME, "") ?: ""
    val isLoggedIn: Boolean get() = prefs.getString(KEY_ACCESS, null) != null

    /**
     * True if the access token has expired (or will expire in the next 60 seconds).
     * The 60s buffer prevents edge cases where the token expires mid-request.
     */
    val isTokenExpired: Boolean
        get() {
            val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
            return System.currentTimeMillis() >= (expiresAt - 60_000L)
        }
}