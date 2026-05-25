package com.richard_salendah.driverantar.ui.vehicle

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddVehicleScreen(
    viewModel: VehicleViewModel,
    onBack: () -> Unit,
    onSuccess: () -> Unit
) {
    val vehicleTypes = viewModel.vehicleTypes
    val uiState      = viewModel.uiState
//    val isLoading    = viewModel.isLoading

    // Keep selectedType in sync once types load from server
    var selectedType     by remember { mutableStateOf(vehicleTypes.firstOrNull()) }
    var typeDropdownOpen by remember { mutableStateOf(false) }
    var licensePlate     by remember { mutableStateOf("") }
    var make             by remember { mutableStateOf("") }
    var model            by remember { mutableStateOf("") }
    var year             by remember { mutableStateOf("") }
    var color            by remember { mutableStateOf("") }

    LaunchedEffect(vehicleTypes) {
        if (selectedType == null && vehicleTypes.isNotEmpty()) {
            selectedType = vehicleTypes.first()
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is VehicleUiState.Success) {
            viewModel.resetState()
            onSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Vehicle") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 20.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ── Loading state for types ───────────────────────────────────────
            if (vehicleTypes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment    = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Loading vehicle types…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (vehicleTypes.isEmpty()) {
                // Types loaded but empty — surface the problem clearly
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Could not load vehicle types. Please go back and try again.",
                        color    = MaterialTheme.colorScheme.onErrorContainer,
                        style    = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                // ── Vehicle type dropdown ─────────────────────────────────────
                Text("Vehicle Type *", style = MaterialTheme.typography.labelLarge)

                ExposedDropdownMenuBox(
                    expanded         = typeDropdownOpen,
                    onExpandedChange = { typeDropdownOpen = it }
                ) {
                    OutlinedTextField(
                        value           = selectedType?.name ?: "Select type",
                        onValueChange   = {},
                        readOnly        = true,
                        trailingIcon    = {
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                        },
                        modifier        = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded         = typeDropdownOpen,
                        onDismissRequest = { typeDropdownOpen = false }
                    ) {
                        vehicleTypes.forEach { vt ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(vt.name,
                                            style = MaterialTheme.typography.bodyMedium)
                                        if (vt.description.isNotBlank()) {
                                            Text(vt.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                },
                                onClick = {
                                    selectedType     = vt
                                    typeDropdownOpen = false
                                }
                            )
                        }
                    }
                }
            }

            // ── License plate (required) ──────────────────────────────────────
            OutlinedTextField(
                value         = licensePlate,
                onValueChange = { licensePlate = it.uppercase() },
                label         = { Text("License Plate *") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth()
            )

            // ── Make ──────────────────────────────────────────────────────────
            OutlinedTextField(
                value         = make,
                onValueChange = { make = it },
                label         = { Text("Make  (e.g. Toyota)") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth()
            )

            // ── Model ─────────────────────────────────────────────────────────
            OutlinedTextField(
                value         = model,
                onValueChange = { model = it },
                label         = { Text("Model  (e.g. Avanza)") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth()
            )

            // ── Year ──────────────────────────────────────────────────────────
            OutlinedTextField(
                value           = year,
                onValueChange   = { year = it },
                label           = { Text("Year") },
                singleLine      = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier        = Modifier.fillMaxWidth()
            )

            // ── Color ─────────────────────────────────────────────────────────
            OutlinedTextField(
                value         = color,
                onValueChange = { color = it },
                label         = { Text("Color") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth()
            )

            // ── Error ─────────────────────────────────────────────────────────
            if (uiState is VehicleUiState.Error) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        (uiState as VehicleUiState.Error).message,
                        color    = MaterialTheme.colorScheme.onErrorContainer,
                        style    = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── Submit ────────────────────────────────────────────────────────
            Button(
                onClick = {
                    val typeId = selectedType?.id ?: return@Button
                    viewModel.addVehicle(
                        vehicleTypeId = typeId,
                        licensePlate  = licensePlate.trim(),
                        make          = make.trim(),
                        model         = model.trim(),
                        year          = year.toIntOrNull() ?: 0,
                        color         = color.trim()
                    )
                },
                enabled  = licensePlate.isNotBlank()
                        && selectedType != null
                        && vehicleTypes.isNotEmpty()
                        && uiState !is VehicleUiState.Loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState is VehicleUiState.Loading) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color       = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Save Vehicle")
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}