package com.sadad.app.feature.lock

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.sadad.app.R
import com.sadad.app.SadadApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object LockTag {
  const val PIN = "lock_pin"
  const val OPEN = "lock_open"
}

/** The biometric prompt behind a seam, so the gate is testable without a sensor. */
internal interface BiometricAuth {
  fun available(): Boolean

  /** Calls [onSuccess] only after a match; cancel and errors leave the PIN screen as it is. */
  fun authenticate(onSuccess: () -> Unit)
}

/**
 * Keeps [content] out of composition behind the PIN or biometric unlock while the lock applies: a
 * store session exists, a PIN is set and the lock is on. Locked from process start and after the
 * app was stopped for more than 1.2 s.
 */
@Composable
fun LockGate(content: @Composable () -> Unit) {
  val context = LocalContext.current.applicationContext
  val app = context as? SadadApplication
  if (app == null) {
    content() // no graph, no store session, nothing to lock (Compose tests)
    return
  }
  val lock = remember(context) { AppLock.get(context) }
  val hasSession by
      produceState<Boolean?>(null, app) {
        val graph = withContext(Dispatchers.IO) { app.graph }
        graph.current.collect { value = it != null }
      }
  LockGate(lock, hasSession, rememberActivityBiometric(), content)
}

@Composable
internal fun LockGate(
    lock: AppLock,
    hasSession: Boolean?,
    biometric: BiometricAuth,
    content: @Composable () -> Unit,
) {
  val locked by lock.locked.collectAsState()
  val enabled by lock.lockEnabled.collectAsState(null)
  val pinSet by lock.pinSet.collectAsState(null)
  LifecycleEventEffect(Lifecycle.Event.ON_STOP) { lock.appStopped() }
  LifecycleEventEffect(Lifecycle.Event.ON_START) { lock.appStarted() }
  val known = hasSession != null && enabled != null && pinSet != null
  val applies = hasSession == true && enabled == true && pinSet == true
  // Lock off, no PIN or signed out: drop the pending lock so a later PIN does not lock at once.
  LaunchedEffect(locked, known, applies) { if (locked && known && !applies) lock.markUnlocked() }
  when {
    !locked || (known && !applies) -> content()
    !known -> Surface(Modifier.fillMaxSize()) {}
    else -> UnlockScreen(lock, biometric)
  }
}

@Composable
private fun UnlockScreen(lock: AppLock, biometric: BiometricAuth) {
  val scope = rememberCoroutineScope()
  val biometricOn by lock.biometricEnabled.collectAsState(false)
  val canBiometric = remember(biometricOn) { biometricOn && biometric.available() }
  var pin by remember { mutableStateOf("") }
  var wrong by remember { mutableStateOf(false) }
  var remaining by remember { mutableLongStateOf(0L) }
  LaunchedEffect(Unit) { remaining = lock.lockout()?.remaining ?: 0L }
  LaunchedEffect(remaining > 0L) {
    while (remaining > 0L) {
      delay(1_000)
      remaining = lock.lockout()?.remaining ?: 0L
    }
  }
  LaunchedEffect(canBiometric) { if (canBiometric) biometric.authenticate(lock::markUnlocked) }
  val locked = remaining > 0L
  val message =
      when {
        locked -> stringResource(R.string.lock_wait, (remaining + 999L) / 1000L)
        wrong -> stringResource(R.string.lock_wrong)
        else -> null
      }
  // Arabic-first, like the rest of the shell.
  CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    Surface(Modifier.fillMaxSize()) {
      Column(
          Modifier.fillMaxSize().padding(24.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.lock_hint))
        OutlinedTextField(
            value = pin,
            onValueChange = {
              pin = it.filter(Char::isDigit).take(12)
              wrong = false
            },
            label = { Text(stringResource(R.string.lock_pin_label)) },
            singleLine = true,
            enabled = !locked,
            isError = message != null,
            supportingText = { if (message != null) Text(message) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth().testTag(LockTag.PIN),
        )
        Button(
            onClick = {
              val entered = pin
              pin = ""
              scope.launch {
                when (val result = lock.verifyPin(entered)) {
                  PinResult.Ok -> lock.markUnlocked()
                  is PinResult.Wrong -> wrong = true
                  is PinResult.LockedOut -> remaining = result.remaining
                }
              }
            },
            enabled = !locked && pin.length >= 4,
            modifier = Modifier.fillMaxWidth().testTag(LockTag.OPEN),
        ) {
          Text(stringResource(R.string.lock_open))
        }
        if (canBiometric) {
          TextButton(onClick = { biometric.authenticate(lock::markUnlocked) }) {
            Text(stringResource(R.string.lock_use_biometric))
          }
        }
      }
    }
  }
}

@Composable
internal fun rememberActivityBiometric(): BiometricAuth {
  val context = LocalContext.current
  return remember(context) { ActivityBiometric(context) }
}

private class ActivityBiometric(private val context: Context) : BiometricAuth {
  private val allowed =
      BiometricManager.Authenticators.BIOMETRIC_STRONG or
          BiometricManager.Authenticators.BIOMETRIC_WEAK

  override fun available(): Boolean =
      BiometricManager.from(context).canAuthenticate(allowed) == BiometricManager.BIOMETRIC_SUCCESS

  override fun authenticate(onSuccess: () -> Unit) {
    val activity = context.fragmentActivity() ?: return
    val info =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.lock_biometric_title))
            .setNegativeButtonText(context.getString(R.string.lock_use_pin))
            .setAllowedAuthenticators(allowed)
            .build()
    val callback =
        object : BiometricPrompt.AuthenticationCallback() {
          override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onSuccess()
          }
        }
    BiometricPrompt(activity, ContextCompat.getMainExecutor(context), callback).authenticate(info)
  }
}

private tailrec fun Context.fragmentActivity(): FragmentActivity? =
    when (this) {
      is FragmentActivity -> this
      is ContextWrapper -> baseContext.fragmentActivity()
      else -> null
    }
