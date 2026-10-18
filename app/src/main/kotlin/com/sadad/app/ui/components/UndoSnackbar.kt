package com.sadad.app.ui.components

import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.sadad.app.R
import kotlinx.coroutines.delay

/**
 * Shows [message] with an undo action for 5 seconds, then runs [onCommit]; undo runs [onUndo]
 * instead. The caller owns [message] so it survives recomposition but not process death, like the
 * Java banner, and must clear it in [onDismiss]. The timer restarts whenever [message] changes.
 */
@Composable
fun UndoSnackbar(
    message: String?,
    onUndo: () -> Unit,
    onCommit: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
  if (message == null) return
  LaunchedEffect(message) {
    delay(5_000)
    onCommit()
    onDismiss()
  }
  Snackbar(
      modifier = modifier,
      action = {
        TextButton(
            onClick = {
              onUndo()
              onDismiss()
            }
        ) {
          Text(stringResource(R.string.undo))
        }
      },
  ) {
    Text(message)
  }
}
