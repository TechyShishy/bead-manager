package com.techyshishy.beadmanager.ui.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: FirebaseAuth,
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    // Named reference so the listener can be removed in onCleared, preventing a
    // memory leak and duplicate fires after configuration changes.
    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        _authState.value = if (firebaseAuth.currentUser != null) {
            AuthState.SignedIn
        } else {
            AuthState.SignedOut
        }
    }

    init {
        _authState.value = if (auth.currentUser != null) AuthState.SignedIn else AuthState.SignedOut
        auth.addAuthStateListener(authStateListener)
    }

    override fun onCleared() {
        super.onCleared()
        auth.removeAuthStateListener(authStateListener)
    }

    /**
     * Launch Google Sign-In via Credential Manager and exchange the resulting
     * ID token for a Firebase credential.
     *
     * [webClientId] must match the OAuth 2.0 client ID registered in the
     * Firebase project's google-services.json (type: web_client_id).
     */
    fun signInWithGoogle(activity: Activity, webClientId: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            _errorMessage.value = null
            try {
                val credentialManager = CredentialManager.create(activity)
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setServerClientId(webClientId)
                    .setFilterByAuthorizedAccounts(false)
                    .build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()
                val result = credentialManager.getCredential(activity, request)
                val googleIdToken = GoogleIdTokenCredential
                    .createFrom(result.credential.data)
                    .idToken
                val firebaseCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                auth.signInWithCredential(firebaseCredential).addOnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        _errorMessage.value = task.exception?.localizedMessage ?: "Sign-in failed"
                        _authState.value = AuthState.SignedOut
                    }
                    // Success: the addAuthStateListener above sets SignedIn.
                }
            } catch (e: Exception) {
                _errorMessage.value = e.localizedMessage ?: "Sign-in failed"
                _authState.value = AuthState.SignedOut
            }
        }
    }

    fun signOut() {
        auth.signOut()
        // addAuthStateListener fires and sets SignedOut automatically.
    }

    fun dismissError() {
        _errorMessage.value = null
    }
}
