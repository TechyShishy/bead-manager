package com.techyshishy.beadmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.techyshishy.beadmanager.ui.adaptive.AdaptiveScaffold
import com.techyshishy.beadmanager.ui.auth.AuthState
import com.techyshishy.beadmanager.ui.auth.AuthViewModel
import com.techyshishy.beadmanager.ui.auth.SignInScreen
import com.techyshishy.beadmanager.ui.theme.BeadManagerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BeadManagerTheme {
                BeadManagerRoot()
            }
        }
    }
}

@Composable
private fun BeadManagerRoot(
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    val authState by authViewModel.authState.collectAsState()
    when (authState) {
        AuthState.Loading -> Unit  // Splash / blank while Firebase initialises
        AuthState.SignedOut -> SignInScreen(authViewModel = authViewModel)
        AuthState.SignedIn -> AdaptiveScaffold()
    }
}
