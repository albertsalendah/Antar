package com.richard_salendah.antar.ui.auth

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import com.richard_salendah.antar.Antar
import com.richard_salendah.antar.data.model.FcmTokenRequest
import com.richard_salendah.antar.data.model.LoginRequest
import com.richard_salendah.antar.data.model.RegisterRequest
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AuthViewModel(app: Application) : AndroidViewModel(app) {

    private val api     = (app as Antar).apiService
    private val session = (app as Antar).sessionManager

    // ── Login state ───────────────────────────────────────────────────────────
    var loginEmail    by mutableStateOf("")
    var loginPassword by mutableStateOf("")
    var loginLoading  by mutableStateOf(false)
    var loginError    by mutableStateOf<String?>(null)

    fun login(onSuccess: () -> Unit) {
        if (loginEmail.isBlank() || loginPassword.isBlank()) {
            loginError = "Email dan password harus diisi"
            return
        }
        viewModelScope.launch {
            loginLoading = true
            loginError   = null
            try {
                val resp = api.login(LoginRequest(loginEmail.trim(), loginPassword))
                if (resp.isSuccessful) {
                    val data = resp.body()?.data
                    if (data != null) {
                        session.saveSession(
                            data.accessToken, data.refreshToken,
                            data.riderId,     data.fullName,
                        )
                        registerFcmToken()
                        onSuccess()
                    } else {
                        loginError = "Login gagal, coba lagi"
                    }
                } else {
                    loginError = parseError(resp.errorBody()?.string())
                        ?: "Email atau password salah"
                }
            } catch (e: Exception) {
                loginError = "Tidak dapat terhubung ke server"
            } finally {
                loginLoading = false
            }
        }
    }

    // ── Register state ────────────────────────────────────────────────────────
    var regFullName        by mutableStateOf("")
    var regEmail           by mutableStateOf("")
    var regPassword        by mutableStateOf("")
    var regConfirmPassword by mutableStateOf("")
    var regPhone           by mutableStateOf("")
    var regLoading         by mutableStateOf(false)
    var regError           by mutableStateOf<String?>(null)
    var regNeedsEmail      by mutableStateOf(false)

    fun register(onSuccess: () -> Unit) {
        when {
            regFullName.isBlank() || regEmail.isBlank() ||
                    regPassword.isBlank() || regConfirmPassword.isBlank() ||
                    regPhone.isBlank() ->
                regError = "Semua kolom harus diisi"

            regPassword.length < 8 ->
                regError = "Password minimal 8 karakter"

            regPassword != regConfirmPassword ->
                regError = "Password dan konfirmasi password tidak cocok"

            // VALID-1: phone must start with 08 or +62
            !isValidPhoneNumber(regPhone.trim()) ->
                regError = "Nomor telepon harus diawali 08 atau +62"

            else -> viewModelScope.launch {
                regLoading = true
                regError   = null
                try {
                    val resp = api.register(
                        RegisterRequest(
                            email       = regEmail.trim(),
                            password    = regPassword,
                            fullName    = regFullName.trim(),
                            phoneNumber = regPhone.trim(),
                        )
                    )
                    if (resp.isSuccessful) {
                        if (resp.body()?.data?.needsConfirmation == true) {
                            regNeedsEmail = true
                        } else {
                            onSuccess()
                        }
                    } else {
                        regError = parseError(resp.errorBody()?.string())
                            ?: "Registrasi gagal, coba lagi"
                    }
                } catch (e: Exception) {
                    regError = "Tidak dapat terhubung ke server"
                } finally {
                    regLoading = false
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * VALID-1: Indonesian phone numbers must start with 08 (local format)
     * or +62 (international format). Both are common in Indonesia.
     * Minimum 9 digits after the prefix to avoid accepting truncated numbers.
     */
    private fun isValidPhoneNumber(phone: String): Boolean {
        return (phone.startsWith("08") || phone.startsWith("+62")) &&
                phone.filter { it.isDigit() }.length >= 9
    }

    private suspend fun registerFcmToken() {
        runCatching {
            val token = getFcmToken() ?: return
            api.saveFcmToken(FcmTokenRequest(token))
        }
    }

    private suspend fun getFcmToken(): String? = suspendCoroutine { cont ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resume(null) }
    }

    private fun parseError(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            org.json.JSONObject(body).optString("error").takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }
}