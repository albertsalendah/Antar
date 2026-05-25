package com.richard_salendah.antar.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "antar_session")

/**
 * Stores auth tokens using DataStore (encrypted at OS level on Android 6+).
 * Never use SharedPreferences for tokens — plain text on disk.
 */
class SessionManager(private val context: Context) {

    companion object {
        private val KEY_ACCESS_TOKEN  = stringPreferencesKey("access_token")
        private val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        private val KEY_RIDER_ID      = stringPreferencesKey("rider_id")
        private val KEY_FULL_NAME     = stringPreferencesKey("full_name")
    }

    suspend fun saveSession(
        accessToken: String,
        refreshToken: String,
        riderId: String,
        fullName: String,
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ACCESS_TOKEN]  = accessToken
            prefs[KEY_REFRESH_TOKEN] = refreshToken
            prefs[KEY_RIDER_ID]      = riderId
            prefs[KEY_FULL_NAME]     = fullName
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit { it.clear() }
    }

    val accessToken: Flow<String?>  = context.dataStore.data.map { it[KEY_ACCESS_TOKEN] }
    val refreshToken: Flow<String?> = context.dataStore.data.map { it[KEY_REFRESH_TOKEN] }
    val riderId: Flow<String?>      = context.dataStore.data.map { it[KEY_RIDER_ID] }
    val fullName: Flow<String?>     = context.dataStore.data.map { it[KEY_FULL_NAME] }

    suspend fun getAccessToken(): String?  = accessToken.first()
    suspend fun getRefreshToken(): String? = refreshToken.first()
    suspend fun getRiderId(): String?      = riderId.first()
    suspend fun isLoggedIn(): Boolean      = getAccessToken() != null
}