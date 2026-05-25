package com.richard_salendah.driverantar.ui.earnings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.richard_salendah.driverantar.data.model.DailyEarning
import com.richard_salendah.driverantar.data.model.EarningsSummary
import com.richard_salendah.driverantar.data.remote.DriverRepository
import com.richard_salendah.driverantar.utils.SessionManager
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class EarningsViewModel(private val repository: DriverRepository) : ViewModel() {

    var summary        by mutableStateOf<EarningsSummary?>(null);      private set
    var dailyEarnings  by mutableStateOf<List<DailyEarning>>(emptyList()); private set
    var isLoading      by mutableStateOf(false);                        private set
    var errorMessage   by mutableStateOf<String?>(null);                private set

    init { load() }

    fun load() {
        viewModelScope.launch {
            isLoading    = true
            errorMessage = null
            val token    = SessionManager.token

            // Fetch both in parallel
            val summaryDef = async { repository.getEarnings(token) }
            val dailyDef   = async { repository.getDailyEarnings(token) }

            summaryDef.await()
                .onSuccess { summary = it }
                .onFailure { e -> errorMessage = e.message ?: "Gagal memuat pendapatan" }

            dailyDef.await()
                .onSuccess { dailyEarnings = it }
                // Daily chart failing silently is acceptable —
                // the summary cards below still show useful data
                .onFailure { }

            isLoading = false
        }
    }
}