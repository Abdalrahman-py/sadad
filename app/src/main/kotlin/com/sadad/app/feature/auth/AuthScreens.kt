package com.sadad.app.feature.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.sadad.app.R
import com.sadad.app.ui.components.PlaceholderScreen

@Composable
fun WelcomeScreen(modifier: Modifier = Modifier) {
  PlaceholderScreen(title = stringResource(R.string.title_welcome), modifier = modifier)
}

@Composable
fun LoginScreen(modifier: Modifier = Modifier) {
  PlaceholderScreen(title = stringResource(R.string.title_login), modifier = modifier)
}

@Composable
fun PasswordChangeScreen(modifier: Modifier = Modifier) {
  PlaceholderScreen(title = stringResource(R.string.title_password_change), modifier = modifier)
}

@Composable
fun AwaitingApprovalScreen(modifier: Modifier = Modifier) {
  PlaceholderScreen(title = stringResource(R.string.title_awaiting_approval), modifier = modifier)
}
