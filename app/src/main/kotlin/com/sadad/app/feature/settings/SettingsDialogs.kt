package com.sadad.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sadad.app.R
import kotlinx.coroutines.delay

/** Stable handles for the dialogs' buttons and fields. */
/** How long the second removal confirmation stays disabled after the first. */
internal const val CONFIRM_DELAY_MS = 1_500L

object SettingsDialogTags {
  const val PROFILE_STORE = "settings:profile:store"
  const val PROFILE_OWNER = "settings:profile:owner"
  const val PROFILE_SAVE = "settings:profile:save"
  const val PIN_FIRST = "settings:pin:first"
  const val PIN_SECOND = "settings:pin:second"
  const val PIN_SAVE = "settings:pin:save"
  const val SIGN_OUT_CONFIRM = "settings:sign-out:confirm"
  const val REMOVE_CONFIRM = "settings:remove:confirm"
}

/** The dialog [SettingsState.pending] asks for. */
@Composable
internal fun SettingsDialogs(
    state: SettingsState,
    store: StoreModule,
    security: SecurityModule,
    account: AccountModule,
    ui: SettingsUi,
) {
  when (val pending = state.pending) {
    is Pending.Profile -> ProfileDialog(pending, store, ui)
    Pending.SetPin -> PinDialog(state.pinError, security, ui)
    is Pending.SignOut -> SignOutDialog(pending, state.busy, account, ui)
    is Pending.Remove -> RemoveDialog(pending, state.busy, account, ui)
    null -> Unit
  }
}

@Composable
private fun ProfileDialog(draft: Pending.Profile, store: StoreModule, ui: SettingsUi) {
  // The fields own their text: a value that makes a round trip through the view model state can
  // lag behind the keyboard and move the cursor. The view model gets every change for the saved
  // state.
  var storeName by remember { mutableStateOf(draft.storeName) }
  var owner by remember { mutableStateOf(draft.ownerName) }
  AlertDialog(
      onDismissRequest = ui::dismiss,
      title = { Text(stringResource(R.string.settings_profile_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
              text = stringResource(R.string.settings_profile_note),
              style = MaterialTheme.typography.labelMedium,
          )
          OutlinedTextField(
              value = storeName,
              onValueChange = {
                storeName = it
                store.onProfileChange(it, owner)
              },
              label = { Text(stringResource(R.string.settings_profile_store_label)) },
              singleLine = true,
              modifier = Modifier.fillMaxWidth().testTag(SettingsDialogTags.PROFILE_STORE),
          )
          OutlinedTextField(
              value = owner,
              onValueChange = {
                owner = it
                store.onProfileChange(storeName, it)
              },
              label = { Text(stringResource(R.string.settings_profile_owner_label)) },
              singleLine = true,
              modifier = Modifier.fillMaxWidth().testTag(SettingsDialogTags.PROFILE_OWNER),
          )
        }
      },
      confirmButton = {
        TextButton(
            onClick = store::saveProfile,
            modifier = Modifier.testTag(SettingsDialogTags.PROFILE_SAVE),
        ) {
          Text(stringResource(R.string.settings_save))
        }
      },
      dismissButton = {
        TextButton(onClick = ui::dismiss) { Text(stringResource(R.string.settings_cancel)) }
      },
  )
}

@Composable
private fun PinDialog(error: PinError?, security: SecurityModule, ui: SettingsUi) {
  // Plain state, not saveable: a PIN must never reach the saved instance state.
  var first by remember { mutableStateOf("") }
  var second by remember { mutableStateOf("") }
  AlertDialog(
      onDismissRequest = ui::dismiss,
      title = { Text(stringResource(R.string.settings_pin_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
              text = stringResource(R.string.settings_pin_message),
              style = MaterialTheme.typography.labelMedium,
          )
          PinField(
              first,
              R.string.settings_pin_new,
              SettingsDialogTags.PIN_FIRST,
              error == PinError.INVALID,
          ) {
            first = it
          }
          PinField(
              second,
              R.string.settings_pin_confirm,
              SettingsDialogTags.PIN_SECOND,
              error == PinError.MISMATCH,
          ) {
            second = it
          }
          when (error) {
            PinError.INVALID -> ErrorText(R.string.settings_pin_invalid)
            PinError.MISMATCH -> ErrorText(R.string.settings_pin_mismatch)
            null -> Unit
          }
        }
      },
      confirmButton = {
        TextButton(
            onClick = { security.savePin(first, second) },
            modifier = Modifier.testTag(SettingsDialogTags.PIN_SAVE),
        ) {
          Text(stringResource(R.string.settings_save))
        }
      },
      dismissButton = {
        TextButton(onClick = ui::dismiss) { Text(stringResource(R.string.settings_cancel)) }
      },
  )
}

@Composable
private fun PinField(
    value: String,
    label: Int,
    tag: String,
    isError: Boolean,
    onChange: (String) -> Unit,
) {
  OutlinedTextField(
      value = value,
      onValueChange = { onChange(it.filter(Char::isDigit).take(4)) },
      label = { Text(stringResource(label)) },
      singleLine = true,
      isError = isError,
      visualTransformation = PasswordVisualTransformation(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
      modifier = Modifier.fillMaxWidth().testTag(tag),
  )
}

@Composable
private fun ErrorText(message: Int) {
  Text(
      text = stringResource(message),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.error,
  )
}

@Composable
private fun SignOutDialog(
    pending: Pending.SignOut,
    busy: Boolean,
    account: AccountModule,
    ui: SettingsUi,
) {
  AlertDialog(
      onDismissRequest = ui::dismiss,
      title = { Text(stringResource(R.string.settings_sign_out)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(R.string.settings_sign_out_message))
          if (pending.unsent > 0) {
            Text(
                text = stringResource(R.string.settings_unsent, pending.unsent.toString()),
                color = MaterialTheme.colorScheme.error,
            )
          }
        }
      },
      confirmButton = {
        TextButton(
            onClick = account::confirmSignOut,
            enabled = !busy,
            modifier = Modifier.testTag(SettingsDialogTags.SIGN_OUT_CONFIRM),
        ) {
          Text(stringResource(R.string.settings_sign_out))
        }
      },
      dismissButton = {
        TextButton(onClick = ui::dismiss) { Text(stringResource(R.string.settings_cancel)) }
      },
  )
}

@Composable
private fun RemoveDialog(
    pending: Pending.Remove,
    busy: Boolean,
    account: AccountModule,
    ui: SettingsUi,
) {
  // The second confirmation must be a separate, read decision: after the first one the button
  // stays off for a moment, so a double tap cannot count twice.
  var ready by remember(pending.confirmed) { mutableStateOf(pending.confirmed == 0) }
  LaunchedEffect(pending.confirmed) {
    if (pending.confirmed > 0) {
      delay(CONFIRM_DELAY_MS)
      ready = true
    }
  }
  AlertDialog(
      onDismissRequest = ui::dismiss,
      title = { Text(stringResource(R.string.settings_remove_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(R.string.settings_remove_message))
          if (pending.unsent > 0) {
            Text(
                text = stringResource(R.string.settings_remove_unsent, pending.unsent.toString()),
                color = MaterialTheme.colorScheme.error,
            )
          }
        }
      },
      confirmButton = {
        TextButton(
            onClick = account::confirmRemove,
            enabled = ready && !busy,
            modifier = Modifier.testTag(SettingsDialogTags.REMOVE_CONFIRM),
        ) {
          Text(
              stringResource(
                  if (pending.confirmed == 0) R.string.settings_confirm
                  else R.string.settings_remove_again
              )
          )
        }
      },
      dismissButton = {
        TextButton(onClick = ui::dismiss) { Text(stringResource(R.string.settings_cancel)) }
      },
  )
}
