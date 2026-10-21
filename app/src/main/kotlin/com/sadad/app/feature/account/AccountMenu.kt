package com.sadad.app.feature.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.sadad.app.R

@Composable
fun AccountMenu(
    storeName: String,
    onPhoto: () -> Unit,
    onAppearance: () -> Unit,
    onNotificationSettings: () -> Unit,
    onStores: () -> Unit,
    onSync: () -> Unit,
    onActivityLog: () -> Unit,
    onDismiss: () -> Unit,
) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(storeName.ifBlank { stringResource(R.string.account_default_store) }) },
      text = {
        Column {
          val items =
              listOf(
                  R.string.account_change_photo to onPhoto,
                  R.string.account_appearance to onAppearance,
                  R.string.account_notification_settings to onNotificationSettings,
                  R.string.account_stores to onStores,
                  R.string.account_sync to onSync,
                  R.string.account_activity to onActivityLog,
              )
          for ((label, action) in items) {
            TextButton(
                onClick = {
                  onDismiss()
                  action()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
              Text(stringResource(label))
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.account_back)) }
      },
  )
}
