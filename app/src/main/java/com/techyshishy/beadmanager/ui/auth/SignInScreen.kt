package com.techyshishy.beadmanager.ui.auth

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.techyshishy.beadmanager.R
import com.techyshishy.beadmanager.BuildConfig

@Composable
fun SignInScreen(authViewModel: AuthViewModel) {
    val errorMessage by authViewModel.errorMessage.collectAsState()
    val activity = LocalActivity.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displayMedium,
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = {
                activity?.let {
                    authViewModel.signInWithGoogle(
                        activity = it,
                        webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID,
                    )
                }
            },
        ) {
            Text(stringResource(R.string.sign_in_with_google))
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(24.dp))
            Snackbar(
                action = {
                    TextButton(onClick = authViewModel::dismissError) {
                        Text("Dismiss")
                    }
                },
            ) {
                Text(errorMessage ?: "")
            }
        }
    }
}
