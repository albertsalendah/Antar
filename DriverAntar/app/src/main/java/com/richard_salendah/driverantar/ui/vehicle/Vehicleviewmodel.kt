package com.richard_salendah.driverantar.ui.vehicle

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.richard_salendah.driverantar.data.model.AddVehicleRequest
import com.richard_salendah.driverantar.data.model.VehicleType
import com.richard_salendah.driverantar.data.remote.DriverRepository
import com.richard_salendah.driverantar.utils.SessionManager
import kotlinx.coroutines.launch

sealed class VehicleUiState {
    object Idle    : VehicleUiState()
    object Loading : VehicleUiState()
    object Success : VehicleUiState()
    data class Error(val message: String) : VehicleUiState()
}

/**
 * Handles the Add Vehicle form only.
 * Vehicle listing and set-active are now managed by ProfileViewModel
 * so the profile screen is the single place drivers manage their vehicles.
 */
class VehicleViewModel(private val repository: DriverRepository) : ViewModel() {

    var vehicleTypes by mutableStateOf<List<VehicleType>>(emptyList()); private set
    var uiState      by mutableStateOf<VehicleUiState>(VehicleUiState.Idle); private set
    var isLoadingTypes by mutableStateOf(false); private set

    init { loadTypes() }

    fun loadTypes() {
        viewModelScope.launch {
            isLoadingTypes = true
            repository.getVehicleTypes(SessionManager.token)
                .onSuccess { vehicleTypes = it }
                .onFailure { uiState = VehicleUiState.Error(it.message ?: "Failed to load types") }
            isLoadingTypes = false
        }
    }

    fun addVehicle(
        vehicleTypeId: Int,
        licensePlate: String,
        make: String,
        model: String,
        year: Int,
        color: String
    ) {
        viewModelScope.launch {
            uiState = VehicleUiState.Loading
            repository.addVehicle(
                token = SessionManager.token,
                req   = AddVehicleRequest(
                    vehicle_type_id = vehicleTypeId,
                    license_plate   = licensePlate,
                    make            = make,
                    model           = model,
                    year            = year,
                    color           = color
                )
            ).onSuccess {
                uiState = VehicleUiState.Success
            }.onFailure { e ->
                uiState = VehicleUiState.Error(e.message ?: "Failed to add vehicle")
            }
        }
    }

    fun resetState() { uiState = VehicleUiState.Idle }
}