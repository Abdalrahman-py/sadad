package com.sadad.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import com.sadad.app.ui.theme.sadadPalette

/**
 * A yes/no question on the white 26dp sheet. A [destructive] confirm («حذف نهائي», «أرشفة») is
 * written in the debt red, as the boards draw it.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
  val p = sadadPalette
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(title, fontWeight = FontWeight.Bold) },
      text = { Text(message, color = p.muted) },
      confirmButton = {
        TextButton(
            onClick = onConfirm,
            colors =
                ButtonDefaults.textButtonColors(
                    contentColor =
                        if (destructive) p.owes else if (p.isDark) p.accent else p.primary
                ),
        ) {
          Text(confirmText, fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(
            onClick = onDismiss,
            colors =
                ButtonDefaults.textButtonColors(
                    contentColor = if (p.isDark) p.accent else p.primary
                ),
        ) {
          Text(dismissText)
        }
      },
  )
}
