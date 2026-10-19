package com.sadad.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sadad.app.ui.SadadApp
import com.sadad.app.ui.theme.SadadTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      var destination by remember { mutableStateOf<Any?>(null) }
      LaunchedEffect(Unit) {
        destination =
            withContext(Dispatchers.IO) {
              (application as SadadApplication).graph.startRoute()
            }
      }
      val route = destination
      if (route == null) Splash() else SadadApp(startDestination = route)
    }
  }
}

/** Shown while the accounts are read off the main thread. */
@Composable
private fun Splash() {
  SadadTheme { Surface(modifier = Modifier.fillMaxSize()) {} }
}
