package com.techyshishy.beadmanager.ui.auth

sealed class AuthState {
    data object Loading : AuthState()
    data object SignedOut : AuthState()
    data object SignedIn : AuthState()
}
