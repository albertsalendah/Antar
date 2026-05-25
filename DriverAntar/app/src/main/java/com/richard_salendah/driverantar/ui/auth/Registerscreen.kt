package com.richard_salendah.driverantar.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun RegisterScreen(
    viewModel: AuthViewModel,
    onNavigateToLogin: () -> Unit
) {
    var fullName         by remember { mutableStateOf("") }
    var email            by remember { mutableStateOf("") }
    var phone            by remember { mutableStateOf("") }
    var password         by remember { mutableStateOf("") }
    var confirmPassword  by remember { mutableStateOf("") }
    var passwordMismatch by remember { mutableStateOf(false) }
    // Controls the "check your email" success dialog
    var showConfirmDialog by remember { mutableStateOf(false) }

    val state = viewModel.state

    // React to ConfirmEmail state — show dialog once
    LaunchedEffect(state) {
        if (state is AuthState.ConfirmEmail) {
            showConfirmDialog = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Create Account", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Join Antar as a driver",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = fullName, onValueChange = { fullName = it },
            label = { Text("Full Name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = phone, onValueChange = { phone = it },
            label = { Text("Phone Number") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it; passwordMismatch = false },
            label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = confirmPassword,
            onValueChange = { confirmPassword = it; passwordMismatch = false },
            label = { Text("Confirm Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = passwordMismatch,
            supportingText = { if (passwordMismatch) Text("Passwords do not match") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        if (state is AuthState.Error) {
            Text(
                text = state.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = {
                if (password != confirmPassword) { passwordMismatch = true; return@Button }
                viewModel.register(fullName, email, password, phone)
            },
            enabled = fullName.isNotBlank() && email.isNotBlank() &&
                    phone.isNotBlank() && password.isNotBlank() &&
                    state !is AuthState.Loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state is AuthState.Loading) {
                CircularProgressIndicator(
                    Modifier.size(20.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Register")
            }
        }

        Spacer(Modifier.height(16.dp))

        TextButton(onClick = onNavigateToLogin) {
            Text("Already have an account? Login")
        }
    }

    // ── "Check your email" dialog ─────────────────────────────────────────────
    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { /* force user to tap the button */ },
            title = { Text("Check Your Email ✉️") },
            text  = {
                Text(
                    "We sent a confirmation link to $email.\n\n" +
                            "Please open the link in that email first, then come back here to log in."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showConfirmDialog = false
                    viewModel.resetState()
                    onNavigateToLogin()   // go to login screen
                }) {
                    Text("Go to Login")
                }
            }
        )
    }
}