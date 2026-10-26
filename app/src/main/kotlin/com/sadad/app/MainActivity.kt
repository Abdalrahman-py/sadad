package com.sadad.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sadad.app.feature.notifications.reminderDestination
import com.sadad.app.feature.settings.ThemePreference
import com.sadad.app.feature.updates.updateRoute
import com.sadad.app.ui.SadadApp
import com.sadad.app.ui.theme.SadadTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {
  private var launchIntent by mutableStateOf<Intent?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    launchIntent = intent
    observeNotificationPermission()
    setContent {
      var destination by remember { mutableStateOf<Any?>(null) }
      val request = launchIntent
      LaunchedEffect(request) {
        destination = null
        destination =
            withContext(Dispatchers.IO) {
              val graph = (application as SadadApplication).graph
              updateRoute(graph, request) ?: reminderDestination(graph, request)
            }
      }
      val route = destination
      val choice by remember { ThemePreference.get(applicationContext).theme }.collectAsState(null)
      val systemDark = isSystemInDarkTheme()
      val dark = choice?.isDark(systemDark) ?: systemDark
      // The system bars follow the chosen theme, not the device's night mode.
      DisposableEffect(dark) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
        )
        onDispose {}
      }
      // Hold the splash until the saved theme is read, so a saved dark theme never flashes light.
      if (route == null || choice == null) Splash(dark)
      else key(route, request) { SadadApp(startDestination = route, dark = dark) }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    launchIntent = intent
  }

  private fun observeNotificationPermission() {
    if (Build.VERSION.SDK_INT < 33) return
    lifecycleScope.launch {
      val accounts =
          withContext(Dispatchers.IO) { (application as SadadApplication).graph.accounts }
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        accounts.state.collect { file ->
          if (!file.notificationsAsked && file.accounts.any { it.sealedRefreshToken != null }) {
            accounts.setNotificationsAsked()
            if (
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
              requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }
          }
        }
      }
    }
  }
}

/** The system navigation bar's scrims, as `enableEdgeToEdge` uses by default. */
private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

/** Shown while the accounts and the saved theme are read off the main thread. */
@Composable
private fun Splash(dark: Boolean) {
  SadadTheme(dark = dark) { Surface(modifier = Modifier.fillMaxSize()) {} }
}
