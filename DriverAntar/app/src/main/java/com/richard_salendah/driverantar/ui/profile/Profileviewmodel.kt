package com.richard_salendah.driverantar.ui.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.richard_salendah.driverantar.data.model.ProfileResponse
import com.richard_salendah.driverantar.data.model.VehicleResponse
import com.richard_salendah.driverantar.data.remote.DriverRepository
import com.richard_salendah.driverantar.utils.SessionManager
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class ProfileViewModel(private val repository: DriverRepository) : ViewModel() {

    var profile          by mutableStateOf<ProfileResponse?>(null);         private set
    var vehicles         by mutableStateOf<List<VehicleResponse>>(emptyList()); private set
    var isLoading        by mutableStateOf(false);                          private set
    var errorMessage     by mutableStateOf<String?>(null);                  private set
    var isUploadingAvatar by mutableStateOf(false);                         private set
    var isSettingActive  by mutableStateOf(false);                          private set

    /**
     * Separate state for the avatar URL so we can update it immediately after
     * upload without triggering a full profile reload (which would cause Coil
     * to load the old cached URL before the new one is ready).
     * We append a cache-busting timestamp query param so Coil fetches fresh.
     */
    var avatarUrl by mutableStateOf<String?>(null)
        private set

    init { load() }

    fun load() {
        viewModelScope.launch {
            isLoading    = true
            errorMessage = null
            val token    = SessionManager.token

            val profileDef  = async { repository.getProfile(token) }
            val vehiclesDef = async { repository.listVehicles(token) }

            profileDef.await()
                .onSuccess {
                    profile   = it
                    // Only set avatarUrl from network if we don't already have a
                    // freshly-uploaded one in memory
                    if (avatarUrl == null) avatarUrl = it.avatar_url
                }
                .onFailure { errorMessage = it.message }

            vehiclesDef.await()
                .onSuccess { vehicles = it }

            isLoading = false
        }
    }

    // ── Avatar ────────────────────────────────────────────────────────────────

    fun uploadAvatar(imageBytes: ByteArray, mimeType: String) {
        viewModelScope.launch {
            isUploadingAvatar = true
            repository.uploadAvatar(SessionManager.token, imageBytes, mimeType)
                .onSuccess { url ->
                    // Append timestamp to bust Coil's disk + memory cache
                    avatarUrl = "$url?t=${System.currentTimeMillis()}"
                    // Also update profile copy
                    profile = profile?.copy(avatar_url = url)
                }
                .onFailure { errorMessage = it.message }
            isUploadingAvatar = false
        }
    }

    // ── Vehicles ──────────────────────────────────────────────────────────────

    /**
     * Sets [vehicleId] as the driver's active vehicle.
     * Only one vehicle can be active at a time — the server handles deactivating
     * any previous selection. We update local state optimistically.
     */
    fun setActiveVehicle(vehicleId: String) {
        viewModelScope.launch {
            isSettingActive = true
            repository.setActiveVehicle(SessionManager.token, vehicleId)
                .onSuccess {
                    // Update local profile state immediately so UI reflects change
                    profile = profile?.copy(active_vehicle_id = vehicleId)
                }
                .onFailure { errorMessage = it.message }
            isSettingActive = false
        }
    }

    fun deleteVehicle(vehicleId: String) {
        viewModelScope.launch {
            repository.deleteVehicle(SessionManager.token, vehicleId)
                .onSuccess {
                    // If deleted vehicle was the active one, clear it
                    if (profile?.active_vehicle_id == vehicleId) {
                        profile = profile?.copy(active_vehicle_id = null)
                    }
                    load()
                }
                .onFailure { errorMessage = it.message }
        }
    }

    fun clearError() { errorMessage = null }
}