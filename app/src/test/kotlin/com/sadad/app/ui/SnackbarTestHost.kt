package com.sadad.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** The shell's shared snackbar host around a screen rendered alone, as screen tests need it. */
@Composable
internal fun WithAppSnackbar(content: @Composable () -> Unit) {
  val snackbar = rememberAppSnackbar()
  CompositionLocalProvider(LocalAppSnackbar provides snackbar) {
    Box {
      content()
      SnackbarHost(snackbar.host, Modifier.align(Alignment.BottomCenter))
    }
  }
}
