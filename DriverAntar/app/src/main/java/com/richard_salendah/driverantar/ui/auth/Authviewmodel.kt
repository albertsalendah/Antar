package com.richard_salendah.driverantar.ui.auth

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import com.richard_salendah.driverantar.data.remote.DriverRepository
import com.richard_salendah.driverantar.utils.SessionManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

sealed class AuthState {
    object Idle         : AuthState()
    object Loading      : AuthState()
    object ConfirmEmail : AuthState()
    data class LoginSuccess(val hasVehicle: Boolean) : AuthState()
    data class Error(val message: String)            : AuthState()
}

class AuthViewModel(private val repository: DriverRepository) : ViewModel() {

    var state by mutableStateOf<AuthState>(AuthState.Idle)
        private set

    // ── Register ──────────────────────────────────────────────────────────────

    fun register(fullName: String, email: String, password: String, phone: String) {
        viewModelScope.launch {
            state = AuthState.Loading
            repository.register(fullName.trim(), email.trim(), password, phone.trim())
                .onSuccess { r ->
                    if (r.needs_confirmation) {
                        state = AuthState.ConfirmEmail
                    } else {
                        SessionManager.save(
                            accessToken      = r.access_token ?: "",
                            refreshToken     = r.refresh_token ?: "",
                            driverId         = r.driver_id ?: "",
                            fullName         = fullName.trim(),
                            expiresInSeconds = 3600L
                        )
                        registerFcmToken()
                        state = AuthState.LoginSuccess(hasVehicle = false)
                    }
                }
                .onFailure { state = AuthState.Error(it.message ?: "Registration failed") }
        }
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    fun login(email: String, password: String) {
        viewModelScope.launch {
            state = AuthState.Loading
            repository.login(email.trim(), password)
                .onSuccess { r ->
                    SessionManager.save(
                        accessToken      = r.access_token,
                        refreshToken     = r.refresh_token,
                        driverId         = r.driver_id,
                        fullName         = r.full_name,
                        expiresInSeconds = 3600L
                    )
                    // Register FCM token immediately after login so the server
                    // can send new_trip notifications to this device.
                    registerFcmToken()
                    checkVehicle()
                }
                .onFailure { state = AuthState.Error(it.message ?: "Login failed") }
        }
    }

    // ── FCM token ─────────────────────────────────────────────────────────────

    /**
     * Gets the current FCM token and registers it with the server.
     * Called after every login. AntarDriverMessagingService.onNewToken() handles
     * subsequent token rotations automatically.
     */
    private fun registerFcmToken() {
        viewModelScope.launch {
            try {
                val fcmToken = FirebaseMessaging.getInstance().token.await()
                repository.saveFcmToken(SessionManager.token, fcmToken)
                    .onFailure { Log.w("AuthViewModel", "FCM token save failed", it) }
                Log.d("AuthViewModel", "FCM token registered")
            } catch (e: Exception) {
                // Non-fatal — driver can still use the app, just won't get push notifications
                Log.w("AuthViewModel", "Could not get FCM token", e)
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun checkVehicle() {
        repository.getProfile(SessionManager.token)
            .onSuccess { state = AuthState.LoginSuccess(hasVehicle = it.active_vehicle_id != null) }
            .onFailure { state = AuthState.LoginSuccess(hasVehicle = true) }
    }

    fun resetState() { state = AuthState.Idle }
}