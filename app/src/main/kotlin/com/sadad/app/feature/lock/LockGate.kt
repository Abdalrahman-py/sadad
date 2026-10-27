package com.sadad.app.feature.lock

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.sadad.app.R
import com.sadad.app.SadadApplication
import com.sadad.app.ui.components.Appear
import com.sadad.app.ui.components.KeypadKey
import com.sadad.app.ui.components.PinDots
import com.sadad.app.ui.components.SadadButton
import com.sadad.app.ui.components.SadadKeypad
import com.sadad.app.ui.components.SadadLogo
import com.sadad.app.ui.components.sadadPageBackground
import com.sadad.app.ui.theme.sadadPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object LockTag {
  const val PIN = "lock_pin"
  const val OPEN = "lock_open"

  fun key(digit: String) = "lock_key_$digit"
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
  val p = sadadPalette
  val keys =
      (1..9).map { KeypadKey.Char(it.toString()) } +
          listOf(
              if (canBiometric)
                  KeypadKey.Action(
                      R.drawable.ic_fingerprint,
                      stringResource(R.string.lock_use_biometric),
                  ) {
                    biometric.authenticate(lock::markUnlocked)
                  }
              else KeypadKey.Blank,
              KeypadKey.Char("0"),
              KeypadKey.Delete(stringResource(R.string.common_delete)),
          )
  // Arabic-first, like the rest of the shell. The Lock board: logo, title, dots, keypad, open.
  CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    Column(
        Modifier.fillMaxSize()
            .sadadPageBackground()
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, top = 24.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Column(
          Modifier.fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Appear { SadadLogo(64.dp, dark = p.isDark) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
              painterResource(R.drawable.ic_lock),
              null,
              tint = if (p.isDark) p.accent else p.primary,
              modifier = Modifier.size(22.dp),
          )
          Text(
              stringResource(R.string.lock_title),
              color = p.text,
              fontSize = 23.sp,
              lineHeight = 32.sp,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.semantics { heading() },
          )
        }
        Text(
            stringResource(if (canBiometric) R.string.lock_hint else R.string.lock_hint_pin),
            color = p.muted,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
        )
      }
      // Outside the scrolling half: on a 640 dp phone with the keyboard up the top block scrolls,
      // and the dots and the open button must stay reachable without scrolling to them.
      PinDots(
          filled = pin.length,
          count = maxOf(4, pin.length),
          description = stringResource(R.string.lock_pin_entered, pin.length),
          modifier = Modifier.testTag(LockTag.PIN),
      )
      Text(
          message ?: if (pin.isEmpty()) stringResource(R.string.lock_pin_range) else "",
          color = if (message != null) p.owes else p.muted,
          fontSize = 13.sp,
          textAlign = TextAlign.Center,
          modifier =
              Modifier.fillMaxWidth().heightIn(min = 20.dp).semantics {
                if (message != null) liveRegion = LiveRegionMode.Polite
              },
      )
      SadadKeypad(
          keys = keys,
          onChar = {
            if (!locked && pin.length < 12) {
              pin += it
              wrong = false
            }
          },
          onDelete = { pin = pin.dropLast(1) },
          keyHeight = 60.dp,
          fontSize = 26,
          plainSpecials = true,
          tagFor = { key -> (key as? KeypadKey.Char)?.let { LockTag.key(it.label) } },
      )
      SadadButton(
          text = stringResource(R.string.lock_open),
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
          height = 56.dp,
          modifier = Modifier.fillMaxWidth().testTag(LockTag.OPEN),
      )
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
