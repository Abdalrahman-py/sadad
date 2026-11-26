package com.sadad.app.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app's one snackbar host. [show] runs in the shell's scope, not the caller's, so a snackbar
 * stays up after the screen that showed it is left.
 */
@Stable
class AppSnackbar(val host: SnackbarHostState, private val scope: CoroutineScope) {
  /**
   * Queues [message]. With [deadlineMillis] the snackbar is gone that long after this call, queued
   * time included, as an undo whose window closes elsewhere needs; TalkBack's longer timeouts never
   * keep it past that.
   */
  fun show(
      message: String,
      actionLabel: String? = null,
      deadlineMillis: Long? = null,
      onResult: (SnackbarResult) -> Unit = {},
  ): Job = scope.launch {
    val duration =
        if (deadlineMillis == null) SnackbarDuration.Short else SnackbarDuration.Indefinite
    val shown = suspend { host.showSnackbar(message, actionLabel, duration = duration) }
    val result =
        if (deadlineMillis == null) shown()
        else withTimeoutOrNull(deadlineMillis) { shown() } ?: SnackbarResult.Dismissed
    onResult(result)
  }
}

/** Null off the shell (a screen test without it): messages then go nowhere. */
val LocalAppSnackbar = staticCompositionLocalOf<AppSnackbar?> { null }

@Composable
internal fun rememberAppSnackbar(): AppSnackbar {
  val scope = rememberCoroutineScope()
  return remember(scope) { AppSnackbar(SnackbarHostState(), scope) }
}

/**
 * Shows [message] once on the shared host each time it changes to a new text, then calls [onShown]
 * so the caller can clear it.
 */
@Composable
fun ShowSnackbar(message: String?, onShown: () -> Unit = {}) {
  val snackbar = LocalAppSnackbar.current
  val shown by rememberUpdatedState(onShown)
  LaunchedEffect(message, snackbar) {
    if (message == null || snackbar == null) return@LaunchedEffect
    snackbar.show(message)
    shown()
  }
}
