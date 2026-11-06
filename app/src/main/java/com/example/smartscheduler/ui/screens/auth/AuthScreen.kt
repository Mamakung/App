package com.example.smartscheduler.ui.screens.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartscheduler.data.repository.AuthRepository
import com.example.smartscheduler.data.repository.AuthResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val isRegisterMode: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val loggedInUid: String? = null
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun onEmailChange(value: String) { _state.value = _state.value.copy(email = value, errorMessage = null) }
    fun onPasswordChange(value: String) { _state.value = _state.value.copy(password = value, errorMessage = null) }
    fun onDisplayNameChange(value: String) { _state.value = _state.value.copy(displayName = value) }
    fun toggleMode() { _state.value = _state.value.copy(isRegisterMode = !_state.value.isRegisterMode, errorMessage = null) }

    fun submit() {
        val s = _state.value
        if (s.email.isBlank() || s.password.isBlank()) {
            _state.value = s.copy(errorMessage = "Please enter both email and password.")
            return
        }
        _state.value = s.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            val result = if (s.isRegisterMode) {
                authRepository.register(s.email.trim(), s.password, s.displayName.trim())
            } else {
                authRepository.login(s.email.trim(), s.password)
            }
            _state.value = when (result) {
                is AuthResult.Success -> _state.value.copy(isLoading = false, loggedInUid = result.uid)
                is AuthResult.Error -> _state.value.copy(isLoading = false, errorMessage = result.message)
            }
        }
    }

    fun sendPasswordReset() {
        val email = _state.value.email
        if (email.isBlank()) {
            _state.value = _state.value.copy(errorMessage = "Enter your email first to reset your password.")
            return
        }
        viewModelScope.launch { authRepository.sendPasswordReset(email.trim()) }
    }
}

@Composable
fun LoginRegisterScreen(
    onAuthenticated: (uid: String) -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.loggedInUid) {
        state.loggedInUid?.let { onAuthenticated(it) }
    }

    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = if (state.isRegisterMode) "Create your account" else "Welcome back",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                "Smart Scheduler",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (state.isRegisterMode) {
                OutlinedTextField(
                    value = state.displayName,
                    onValueChange = viewModel::onDisplayNameChange,
                    label = { Text("Display name") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            OutlinedTextField(
                value = state.email,
                onValueChange = viewModel::onEmailChange,
                label = { Text("Email") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )

            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = viewModel::submit,
                enabled = !state.isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (state.isRegisterMode) "Register" else "Log in")
                }
            }

            TextButton(onClick = viewModel::toggleMode) {
                Text(
                    if (state.isRegisterMode) "Already have an account? Log in"
                    else "No account yet? Register"
                )
            }
            if (!state.isRegisterMode) {
                TextButton(onClick = viewModel::sendPasswordReset) {
                    Text("Forgot password?")
                }
            }
        }
    }
}
