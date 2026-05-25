package com.richard_salendah.antar.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

private val PrimaryBlue = Color(0xFF1B6CA8)

@Composable
fun RegisterScreen(
    onRegisterSuccess: () -> Unit,
    onNavigateToLogin: () -> Unit,
    viewModel: AuthViewModel = viewModel(),
) {
    val focusManager = LocalFocusManager.current
    var passwordVisible        by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }

    // ── Email confirmation dialog ─────────────────────────────────────────────
    if (viewModel.regNeedsEmail) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Konfirmasi Email") },
            text = {
                Text(
                    "Akun berhasil dibuat! Silakan cek email Anda dan klik link " +
                            "konfirmasi sebelum masuk."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.regNeedsEmail = false
                    onRegisterSuccess()
                }) {
                    Text("OK, Mengerti")
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
    ) {
        // ── Top bar ───────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PrimaryBlue)
                .statusBarsPadding()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigateToLogin) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Kembali",
                    tint = Color.White,
                )
            }
            Text(
                text = "Buat Akun Baru",
                style = MaterialTheme.typography.titleLarge.copy(
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }

        // ── Form ──────────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Text(
                text = "Lengkapi data diri Anda untuk memulai",
                style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF888888)),
                modifier = Modifier.padding(bottom = 24.dp),
            )

            // Full name
            OutlinedTextField(
                value = viewModel.regFullName,
                onValueChange = {
                    viewModel.regFullName = it
                    viewModel.regError = null
                },
                label = { Text("Nama Lengkap") },
                leadingIcon = {
                    Icon(Icons.Default.Person, contentDescription = null, tint = PrimaryBlue)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Email
            OutlinedTextField(
                value = viewModel.regEmail,
                onValueChange = {
                    viewModel.regEmail = it
                    viewModel.regError = null
                },
                label = { Text("Email") },
                leadingIcon = {
                    Icon(Icons.Default.Email, contentDescription = null, tint = PrimaryBlue)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Password
            OutlinedTextField(
                value = viewModel.regPassword,
                onValueChange = {
                    viewModel.regPassword = it
                    viewModel.regError = null
                },
                label = { Text("Password") },
                leadingIcon = {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = PrimaryBlue)
                },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff
                            else Icons.Default.Visibility,
                            contentDescription = null,
                            tint = Color(0xFF888888),
                        )
                    }
                },
                visualTransformation = if (passwordVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                ),
                supportingText = {
                    Text(
                        "Minimal 8 karakter",
                        color = Color(0xFFAAAAAA),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Confirm password
            val passwordMismatch = viewModel.regConfirmPassword.isNotEmpty() &&
                    viewModel.regPassword != viewModel.regConfirmPassword
            OutlinedTextField(
                value = viewModel.regConfirmPassword,
                onValueChange = {
                    viewModel.regConfirmPassword = it
                    viewModel.regError = null
                },
                label = { Text("Konfirmasi Password") },
                leadingIcon = {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = PrimaryBlue)
                },
                trailingIcon = {
                    IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                        Icon(
                            imageVector = if (confirmPasswordVisible) Icons.Default.VisibilityOff
                            else Icons.Default.Visibility,
                            contentDescription = null,
                            tint = Color(0xFF888888),
                        )
                    }
                },
                visualTransformation = if (confirmPasswordVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                singleLine = true,
                isError = passwordMismatch,
                supportingText = if (passwordMismatch) {
                    { Text("Password tidak cocok", color = MaterialTheme.colorScheme.error) }
                } else null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Phone number
            OutlinedTextField(
                value = viewModel.regPhone,
                onValueChange = {
                    viewModel.regPhone = it
                    viewModel.regError = null
                },
                label = { Text("Nomor Telepon") },
                leadingIcon = {
                    Icon(Icons.Default.Phone, contentDescription = null, tint = PrimaryBlue)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        viewModel.register(onRegisterSuccess)
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            // Inline error from ViewModel
            if (viewModel.regError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = viewModel.regError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Register button
            Button(
                onClick = {
                    focusManager.clearFocus()
                    viewModel.register(onRegisterSuccess)
                },
                enabled = !viewModel.regLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
            ) {
                if (viewModel.regLoading) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                    )
                } else {
                    Text(
                        text = "Daftar",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Login link
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Sudah punya akun? ",
                    style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF666666)),
                )
                TextButton(
                    onClick = onNavigateToLogin,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Text(
                        text = "Masuk",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = PrimaryBlue,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }
        }
    }
}