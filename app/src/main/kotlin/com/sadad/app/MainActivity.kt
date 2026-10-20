package com.sadad.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sadad.app.feature.notifications.reminderDestination
import com.sadad.app.ui.SadadApp
import com.sadad.app.ui.theme.SadadTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
  private var launchIntent by mutableStateOf<Intent?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    launchIntent = intent
    observeNotificationPermission()
    setContent {
      var destination by remember { mutableStateOf<Any?>(null) }
      val request = launchIntent
      LaunchedEffect(request) {
        destination = null
        destination =
            withContext(Dispatchers.IO) {
              reminderDestination((application as SadadApplication).graph, request)
            }
      }
      val route = destination
      if (route == null) Splash() else key(route, request) { SadadApp(startDestination = route) }
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

/** Shown while the accounts are read off the main thread. */
@Composable
private fun Splash() {
  SadadTheme { Surface(modifier = Modifier.fillMaxSize()) {} }
}
