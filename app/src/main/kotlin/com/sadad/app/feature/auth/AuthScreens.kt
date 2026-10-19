package com.sadad.app.feature.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadad.app.R

object AuthTags {
  const val WELCOME_TITLE = "auth:welcome-title"
  const val WELCOME_START = "auth:welcome-start"
  const val USERNAME = "auth:username"
  const val PASSWORD = "auth:password"
  const val LOGIN_BUTTON = "auth:login-button"
  const val PASSWORD_CURRENT = "auth:password-current"
  const val PASSWORD_NEW = "auth:password-new"
  const val PASSWORD_CONFIRM = "auth:password-confirm"
  const val PASSWORD_SAVE = "auth:password-save"
  const val STAFF_NAME = "auth:staff-name"
  const val DEVICE_LABEL = "auth:device-label"
  const val APPROVAL_SAVE = "auth:approval-save"
  const val APPROVAL_CHECK_AGAIN = "auth:approval-check-again"
}

@Composable
fun WelcomeScreen(
    modifier: Modifier = Modifier,
    onNavigate: (Any) -> Unit = {},
    viewModel: WelcomeViewModel = authViewModel { graph, _, _ -> WelcomeViewModel(graph) },
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  AuthDestination(state.destination, onNavigate)
  if (!state.showWelcome || state.destination != null) return

  Column(
      modifier = modifier.fillMaxSize().padding(28.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
  ) {
    Text(
        text = stringResource(R.string.welcome_eyebrow),
        color = MaterialTheme.colorScheme.secondary,
        style = MaterialTheme.typography.labelLarge,
    )
    Text(
        text = stringResource(R.string.title_welcome),
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.testTag(AuthTags.WELCOME_TITLE),
    )
    Text(
        text = stringResource(R.string.welcome_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
    )
    Button(
        onClick = viewModel::continueToLogin,
        modifier = Modifier.fillMaxWidth().testTag(AuthTags.WELCOME_START),
    ) {
      Text(stringResource(R.string.welcome_start))
    }
    Text(
        text = stringResource(R.string.welcome_footer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 18.dp),
    )
  }
}

@Composable
fun LoginScreen(
    modifier: Modifier = Modifier,
    onNavigate: (Any) -> Unit = {},
    viewModel: LoginViewModel = authViewModel { graph, saved, context ->
      LoginViewModel(graph, saved, context)
    },
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  AuthDestination(state.destination, onNavigate)
  if (state.destination != null) return
  val context = LocalContext.current

  Column(
      modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
  ) {
    Text(
        text = stringResource(R.string.login_eyebrow),
        color = MaterialTheme.colorScheme.secondary,
        style = MaterialTheme.typography.labelLarge,
    )
    Text(
        text = stringResource(R.string.title_login),
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        text = stringResource(R.string.login_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
    )
    OutlinedTextField(
        value = state.username,
        onValueChange = viewModel::onUsernameChange,
        label = { Text(stringResource(R.string.login_username)) },
        singleLine = true,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().testTag(AuthTags.USERNAME),
    )
    OutlinedTextField(
        value = state.password,
        onValueChange = viewModel::onPasswordChange,
        label = { Text(stringResource(R.string.login_password)) },
        singleLine = true,
        enabled = !state.busy,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(AuthTags.PASSWORD),
    )
    AuthError(state.error)
    Button(
        onClick = viewModel::signIn,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag(AuthTags.LOGIN_BUTTON),
    ) {
      Text(stringResource(R.string.login_submit))
    }
    TextButton(onClick = { openSupport(context) }, modifier = Modifier.padding(top = 12.dp)) {
      Text(stringResource(R.string.login_support))
    }
  }
}

@Composable
fun PasswordChangeScreen(
    modifier: Modifier = Modifier,
    onNavigate: (Any) -> Unit = {},
    viewModel: PasswordChangeViewModel = authViewModel { graph, _, context ->
      PasswordChangeViewModel(graph, context)
    },
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  AuthDestination(state.destination, onNavigate)
  if (state.destination != null) return

  Column(
      modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
  ) {
    Text(
        text = stringResource(R.string.title_password_change),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
    Text(
        text = stringResource(R.string.password_change_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
    )
    PasswordField(
        value = state.current,
        onValueChange = viewModel::onCurrentChange,
        label = stringResource(R.string.password_current),
        tag = AuthTags.PASSWORD_CURRENT,
        enabled = !state.busy,
    )
    PasswordField(
        value = state.new,
        onValueChange = viewModel::onNewChange,
        label = stringResource(R.string.password_new),
        tag = AuthTags.PASSWORD_NEW,
        enabled = !state.busy,
    )
    PasswordField(
        value = state.confirm,
        onValueChange = viewModel::onConfirmChange,
        label = stringResource(R.string.password_confirm),
        tag = AuthTags.PASSWORD_CONFIRM,
        enabled = !state.busy,
    )
    AuthError(state.error)
    Button(
        onClick = viewModel::change,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag(AuthTags.PASSWORD_SAVE),
    ) {
      Text(stringResource(R.string.password_save))
    }
  }
}

@Composable
fun AwaitingApprovalScreen(
    modifier: Modifier = Modifier,
    onNavigate: (Any) -> Unit = {},
    viewModel: AwaitingApprovalViewModel = authViewModel { graph, _, context ->
      AwaitingApprovalViewModel(graph, context)
    },
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  AuthDestination(state.destination, onNavigate)
  if (state.destination != null || state.loading) return

  Column(
      modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
  ) {
    Text(
        text = stringResource(R.string.title_awaiting_approval),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
    if (state.needsDetails) {
      Text(
          text = stringResource(R.string.approval_details_hint),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
      )
      OutlinedTextField(
          value = state.staffName,
          onValueChange = viewModel::onStaffNameChange,
          label = { Text(stringResource(R.string.approval_staff_name)) },
          singleLine = true,
          enabled = !state.busy,
          modifier = Modifier.fillMaxWidth().testTag(AuthTags.STAFF_NAME),
      )
      OutlinedTextField(
          value = state.deviceLabel,
          onValueChange = viewModel::onDeviceLabelChange,
          label = { Text(stringResource(R.string.approval_device_label)) },
          singleLine = true,
          enabled = !state.busy,
          modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(AuthTags.DEVICE_LABEL),
      )
      Button(
          onClick = viewModel::save,
          enabled = !state.busy,
          modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag(AuthTags.APPROVAL_SAVE),
      ) {
        Text(stringResource(R.string.approval_save))
      }
    } else {
      state.statusText?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp, bottom = 18.dp),
        )
      }
      Button(
          onClick = viewModel::checkAgain,
          enabled = !state.busy,
          modifier = Modifier.fillMaxWidth().testTag(AuthTags.APPROVAL_CHECK_AGAIN),
      ) {
        Text(stringResource(R.string.approval_check_again))
      }
      if (state.busy) {
        CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
      }
    }
    AuthError(state.error, TextAlign.Center)
  }
}

@Composable
private fun AuthDestination(destination: Any?, onNavigate: (Any) -> Unit) {
  LaunchedEffect(destination) { destination?.let(onNavigate) }
}

@Composable
private fun AuthError(text: String?, textAlign: TextAlign? = null) {
  if (text == null) return
  Text(
      text = text,
      color = MaterialTheme.colorScheme.error,
      style = MaterialTheme.typography.bodyMedium,
      textAlign = textAlign,
      modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
  )
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    tag: String,
    enabled: Boolean,
) {
  OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      label = { Text(label) },
      singleLine = true,
      enabled = enabled,
      visualTransformation = PasswordVisualTransformation(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
      modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag(tag),
  )
}

private fun openSupport(context: Context) {
  val number = context.getString(R.string.support_whatsapp_number)
  try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number")))
  } catch (_: ActivityNotFoundException) {
    Toast.makeText(context, R.string.support_unavailable, Toast.LENGTH_SHORT).show()
  }
}
