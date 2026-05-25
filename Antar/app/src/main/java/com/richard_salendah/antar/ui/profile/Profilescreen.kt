package com.richard_salendah.antar.ui.profile

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.richard_salendah.antar.Antar
import com.richard_salendah.antar.data.model.UpdateProfileRequest
import com.richard_salendah.antar.ui.common.ProfileSkeleton
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

// ── ViewModel ─────────────────────────────────────────────────────────────────

class ProfileViewModel(app: Application) : AndroidViewModel(app) {

    private val api     = (app as Antar).apiService
    private val session = (app as Antar).sessionManager

    var fullName    by mutableStateOf("")
    var email       by mutableStateOf("")
    var phone       by mutableStateOf("")
    var avatarUrl   by mutableStateOf<String?>(null)
    var islandName  by mutableStateOf<String?>(null)

    var loading       by mutableStateOf(false)
    var saving        by mutableStateOf(false)
    var avatarLoading by mutableStateOf(false)
    var error         by mutableStateOf<String?>(null)
    var saveSuccess   by mutableStateOf(false)

    var editFullName by mutableStateOf("")
    var editEmail    by mutableStateOf("")
    var isEditing    by mutableStateOf(false)

    init { loadProfile() }

    fun loadProfile() {
        viewModelScope.launch {
            loading = true
            error   = null
            runCatching {
                val resp = api.getProfile()
                if (resp.isSuccessful) {
                    resp.body()?.data?.let { p ->
                        fullName     = p.fullName
                        email        = p.email
                        phone        = p.phoneNumber
                        avatarUrl    = p.avatarUrl
                        islandName   = p.islandName
                        editFullName = p.fullName
                        editEmail    = p.email
                    }
                } else {
                    error = "Gagal memuat profil"
                }
            }.onFailure { error = "Tidak dapat terhubung ke server" }
            loading = false
        }
    }

    fun saveProfile() {
        viewModelScope.launch {
            saving      = true
            error       = null
            saveSuccess = false
            runCatching {
                val resp = api.updateProfile(
                    UpdateProfileRequest(
                        fullName = editFullName.trim().ifEmpty { null },
                        email    = editEmail.trim().ifEmpty { null },
                    )
                )
                if (resp.isSuccessful) {
                    fullName    = editFullName.trim().ifEmpty { fullName }
                    email       = editEmail.trim().ifEmpty { email }
                    isEditing   = false
                    saveSuccess = true
                } else {
                    error = "Gagal menyimpan profil"
                }
            }.onFailure { error = "Tidak dapat terhubung ke server" }
            saving = false
        }
    }

    fun uploadAvatar(context: Context, uri: Uri) {
        viewModelScope.launch {
            avatarLoading = true
            error         = null
            runCatching {
                val bytes    = context.contentResolver.openInputStream(uri)?.readBytes() ?: return@runCatching
                val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                val part     = MultipartBody.Part.createFormData(
                    "avatar", "avatar.jpg",
                    bytes.toRequestBody(mimeType.toMediaTypeOrNull()),
                )
                val resp = api.uploadAvatar(part)
                if (resp.isSuccessful) avatarUrl = resp.body()?.data?.avatarUrl
                else error = "Gagal mengunggah foto"
            }.onFailure { error = "Gagal mengunggah foto" }
            avatarLoading = false
        }
    }

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch { session.clearSession(); onLoggedOut() }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

private val PrimaryBlue = Color(0xFF1B6CA8)

@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit,
    viewModel: ProfileViewModel = viewModel(),
) {
    val context = LocalContext.current
    var showLogoutDialog by remember { mutableStateOf(false) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let { viewModel.uploadAvatar(context, it) } }

    // Auto-clear save success toast after 2 s
    LaunchedEffect(viewModel.saveSuccess) {
        if (viewModel.saveSuccess) {
            kotlinx.coroutines.delay(2_000)
            viewModel.saveSuccess = false
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title            = { Text("Keluar?") },
            text             = { Text("Anda akan keluar dari akun ini.") },
            confirmButton    = {
                Button(
                    onClick = { viewModel.logout(onLogout) },
                    colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                ) { Text("Keluar") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("Batal") }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF4F6F9))) {

        // ── Top bar ───────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .statusBarsPadding()
                .padding(end = 8.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = PrimaryBlue)
                }
                Text(
                    "Profil Saya",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold, color = Color(0xFF1A1A1A),
                    ),
                )
            }
            if (!viewModel.loading) {
                if (viewModel.isEditing) {
                    Row {
                        TextButton(onClick = {
                            viewModel.isEditing   = false
                            viewModel.editFullName = viewModel.fullName
                            viewModel.editEmail    = viewModel.email
                        }) { Text("Batal", color = Color(0xFF888888)) }
                        IconButton(onClick = { viewModel.saveProfile() }, enabled = !viewModel.saving) {
                            if (viewModel.saving)
                                CircularProgressIndicator(Modifier.size(20.dp), color = PrimaryBlue, strokeWidth = 2.dp)
                            else
                                Icon(Icons.Default.Check, "Simpan", tint = PrimaryBlue)
                        }
                    }
                } else {
                    IconButton(onClick = { viewModel.isEditing = true }) {
                        Icon(Icons.Default.Edit, "Edit", tint = PrimaryBlue)
                    }
                }
            }
        }

        // ── Shimmer on first load ─────────────────────────────────────────────
        if (viewModel.loading) {
            ProfileSkeleton()
            return@Column
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            // ── Avatar ────────────────────────────────────────────────────────
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box {
                    if (!viewModel.avatarUrl.isNullOrEmpty()) {
                        AsyncImage(
                            model              = viewModel.avatarUrl,
                            contentDescription = "Avatar",
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier.size(96.dp).clip(CircleShape),
                        )
                    } else {
                        Surface(shape = CircleShape, color = Color(0xFFE8F4FD), modifier = Modifier.size(96.dp)) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(Icons.Default.Person, null, tint = PrimaryBlue, modifier = Modifier.size(52.dp))
                            }
                        }
                    }
                    Surface(
                        shape    = CircleShape,
                        color    = PrimaryBlue,
                        modifier = Modifier
                            .size(30.dp)
                            .align(Alignment.BottomEnd)
                            .clickable { galleryLauncher.launch("image/*") },
                    ) {
                        if (viewModel.avatarLoading) {
                            CircularProgressIndicator(
                                color = Color.White, modifier = Modifier.padding(5.dp), strokeWidth = 2.dp)
                        } else {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(Icons.Default.CameraAlt, "Ubah foto",
                                    tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(viewModel.fullName,
                style    = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A)),
                modifier = Modifier.align(Alignment.CenterHorizontally))
            if (!viewModel.islandName.isNullOrBlank()) {
                Text(viewModel.islandName!!,
                    style    = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF888888)),
                    modifier = Modifier.align(Alignment.CenterHorizontally))
            }

            Spacer(Modifier.height(24.dp))

            // ── Info card ─────────────────────────────────────────────────────
            Card(
                modifier  = Modifier.fillMaxWidth(),
                shape     = RoundedCornerShape(16.dp),
                colors    = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(1.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (viewModel.isEditing) {
                        OutlinedTextField(
                            value         = viewModel.editFullName,
                            onValueChange = { viewModel.editFullName = it },
                            label         = { Text("Nama Lengkap") },
                            leadingIcon   = { Icon(Icons.Default.Person, null, tint = PrimaryBlue) },
                            singleLine    = true,
                            modifier      = Modifier.fillMaxWidth(),
                            shape         = RoundedCornerShape(10.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value         = viewModel.editEmail,
                            onValueChange = { viewModel.editEmail = it },
                            label         = { Text("Email") },
                            leadingIcon   = { Icon(Icons.Default.Email, null, tint = PrimaryBlue) },
                            singleLine    = true,
                            modifier      = Modifier.fillMaxWidth(),
                            shape         = RoundedCornerShape(10.dp),
                        )
                    } else {
                        ProfileRow(Icons.Default.Person, "Nama",     viewModel.fullName)
                        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color(0xFFF0F0F0))
                        ProfileRow(Icons.Default.Email,  "Email",    viewModel.email.ifEmpty { "—" })
                        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color(0xFFF0F0F0))
                        ProfileRow(Icons.Default.Phone,  "Telepon",  viewModel.phone)
                    }
                }
            }

            // Feedback
            if (viewModel.saveSuccess) {
                Spacer(Modifier.height(8.dp))
                Text("Profil berhasil diperbarui", color = Color(0xFF2E7D32),
                    style    = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            if (viewModel.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(viewModel.error!!, color = MaterialTheme.colorScheme.error,
                    style    = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
            }

            Spacer(Modifier.height(32.dp))

            // ── Logout ────────────────────────────────────────────────────────
            Button(
                onClick  = { showLogoutDialog = true },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape    = RoundedCornerShape(12.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFEBEE),
                    contentColor   = Color(0xFFE53935),
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Keluar dari Akun", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun ProfileRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = PrimaryBlue, modifier = Modifier.size(20.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFAAAAAA)))
            Text(value, style = MaterialTheme.typography.bodyMedium.copy(
                color = Color(0xFF1A1A1A), fontWeight = FontWeight.Medium))
        }
    }
}