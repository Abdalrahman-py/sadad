package com.sadad.app.feature.updates

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadad.app.BuildConfig
import com.sadad.app.R
import com.sadad.app.ui.components.ScreenHeader
import com.sadad.app.ui.theme.sadadPalette
import java.util.Locale

/** Stable handles for the tests and for anything that needs to find a control by name. */
object UpdateTags {
  const val SCREEN = "update:screen"
  const val ACTION = "update:action"
  const val SECONDARY = "update:secondary"
  const val PROGRESS = "update:progress"
}

/**
 * One screen for every state of the update: checking, available, downloading, ready, installing and
 * failed. There is no blocking gate — a security release is never forced on someone mid-entry; it
 * stays on the settings row until it is installed.
 */
@Composable
fun AppUpdateScreen(onBack: (() -> Unit)? = null, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val center = remember { UpdateCenter.get(context) }
  val state by center.state.collectAsStateWithLifecycle()
  LaunchedEffect(Unit) {
    center.restore()
    center.check(force = true)
  }
  val p = sadadPalette
  Scaffold(
      modifier = modifier.testTag(UpdateTags.SCREEN),
      topBar = { ScreenHeader(stringResource(R.string.updates_title), onBack = onBack) },
  ) { padding ->
    Column(
        Modifier.fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      UpdateCard {
        Text(
            stringResource(R.string.updates_installed, BuildConfig.VERSION_NAME),
            color = p.muted,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        )
        Text(
            stringResource(
                R.string.updates_channel,
                stringResource(
                    if (UpdateChannel.current() == UpdateChannel.STABLE) {
                      R.string.updates_channel_stable
                    } else {
                      R.string.updates_channel_team
                    }
                ),
            ),
            color = p.muted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        val manifest = state.manifest
        if (manifest != null) {
          Text(
              stringResource(R.string.updates_new_version, manifest.versionName),
              color = p.text,
              fontSize = 17.sp,
              lineHeight = 24.sp,
              fontWeight = FontWeight.Bold,
          )
          Text(
              stringResource(R.string.updates_size, sizeText(manifest.sizeBytes)),
              color = p.muted,
              fontSize = 13.sp,
              lineHeight = 20.sp,
          )
        }
      }
      val manifest = state.manifest
      if (manifest != null && manifest.notes.isNotBlank()) {
        UpdateCard {
          Text(
              manifest.notes,
              color = p.text,
              fontSize = 14.sp,
              lineHeight = 22.sp,
          )
        }
      }
      Status(state)
      Actions(state, center, context, onBack)
    }
  }
}

@Composable
private fun Status(state: UpdateState) {
  val p = sadadPalette
  val error = state.error
  when {
    state.phase == UpdateState.Phase.DOWNLOADING -> {
      LinearProgressIndicator(
          progress = { state.progress / 100f },
          modifier = Modifier.fillMaxWidth().testTag(UpdateTags.PROGRESS),
          color = p.primary,
      )
      Text(
          stringResource(R.string.updates_downloading, state.progress),
          color = p.muted,
          fontSize = 13.sp,
          lineHeight = 20.sp,
      )
    }
    error != null -> {
      Text(
          stringResource(error.text()),
          color = p.muted,
          fontSize = 13.sp,
          lineHeight = 20.sp,
      )
    }
    state.phase == UpdateState.Phase.CHECKING -> {
      Text(
          stringResource(R.string.updates_checking),
          color = p.muted,
          fontSize = 13.sp,
          lineHeight = 20.sp,
      )
    }
    state.phase == UpdateState.Phase.UP_TO_DATE -> {
      Text(
          stringResource(R.string.updates_up_to_date),
          color = p.muted,
          fontSize = 13.sp,
          lineHeight = 20.sp,
      )
    }
    state.phase == UpdateState.Phase.READY && state.manifest != null -> {
      Text(
          stringResource(R.string.updates_ready),
          color = p.muted,
          fontSize = 13.sp,
          lineHeight = 20.sp,
      )
    }
    state.phase == UpdateState.Phase.INSTALLING -> {
      Text(
          stringResource(R.string.updates_installing),
          color = p.muted,
          fontSize = 13.sp,
          lineHeight = 20.sp,
      )
    }
  }
}

@Composable
private fun Actions(
    state: UpdateState,
    center: UpdateCenter,
    context: Context,
    onBack: (() -> Unit)?,
) {
  val p = sadadPalette
  val error = state.error
  Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
    if (state.phase == UpdateState.Phase.CHECKING) {
      CircularProgressIndicator(color = p.primary, modifier = Modifier.size(28.dp))
    }
    val primary: Pair<Int, () -> Unit>? =
        when (state.phase) {
          UpdateState.Phase.AVAILABLE -> R.string.updates_download to center::download
          UpdateState.Phase.READY -> R.string.updates_install to center::install
          UpdateState.Phase.FAILED ->
              if (state.manifest != null) R.string.updates_retry to center::download
              else R.string.updates_check_again to center::checkNow
          UpdateState.Phase.UP_TO_DATE,
          UpdateState.Phase.IDLE -> R.string.updates_check_again to center::checkNow
          else -> null
        }
    val busy = state.phase in setOf(UpdateState.Phase.CHECKING, UpdateState.Phase.INSTALLING)
    if (primary != null) {
      Button(
          onClick = primary.second,
          enabled = !busy,
          modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(UpdateTags.ACTION),
          colors =
              ButtonDefaults.buttonColors(containerColor = p.primary, contentColor = Color.White),
      ) {
        Text(stringResource(primary.first), fontSize = 15.sp, fontWeight = FontWeight.Bold)
      }
    }
    if (error == UpdateError.NEEDS_PERMISSION) {
      OutlinedButton(
          onClick = { open(context, UpdateInstaller.allowInstallsIntent(context)) },
          modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
      ) {
        Text(stringResource(R.string.updates_open_settings), fontSize = 14.sp)
      }
    }
    if (error == UpdateError.SIGNATURE_MISMATCH) {
      val url = center.apkUrl()
      if (url != null) {
        OutlinedButton(
            onClick = { open(context, Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
          Text(stringResource(R.string.updates_download_in_browser), fontSize = 14.sp)
        }
      }
    }
    if (state.phase == UpdateState.Phase.DOWNLOADING) {
      TextButton(
          onClick = center::cancelDownload,
          modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
      ) {
        Text(stringResource(R.string.updates_cancel), fontSize = 14.sp)
      }
    }
    if (state.hasUpdate && state.phase != UpdateState.Phase.INSTALLING) {
      TextButton(
          onClick = {
            center.snooze()
            onBack?.invoke()
          },
          modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(UpdateTags.SECONDARY),
      ) {
        Text(stringResource(R.string.updates_later), fontSize = 14.sp, color = p.muted)
      }
    }
  }
}

/** A card in the settings board's shape: `--card`, a 1 dp `--line`, 13 dp corners. */
@Composable
private fun UpdateCard(content: @Composable () -> Unit) {
  val p = sadadPalette
  Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(13.dp),
      color = p.card,
      border = BorderStroke(1.dp, p.line),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
  }
}

private fun open(context: Context, intent: Intent) =
    try {
      context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
      // No browser, no settings page: the sentence above already says what to do.
    }

/** `5.4` for 5,662,310 bytes; Arabic keeps Latin digits for sizes elsewhere in the app. */
private fun sizeText(bytes: Long): String = String.format(Locale.US, "%.1f", bytes / 1048576.0)

private fun UpdateError.text(): Int =
    when (this) {
      UpdateError.NETWORK -> R.string.updates_error_network
      UpdateError.NO_MANIFEST -> R.string.updates_error_no_manifest
      UpdateError.BAD_MANIFEST -> R.string.updates_error_bad_manifest
      UpdateError.DOWNLOAD_FAILED -> R.string.updates_error_download
      UpdateError.NO_SPACE -> R.string.updates_error_no_space
      UpdateError.CHECKSUM_MISMATCH -> R.string.updates_error_checksum
      UpdateError.SIGNATURE_MISMATCH -> R.string.updates_error_signature
      UpdateError.NEEDS_PERMISSION -> R.string.updates_error_permission
      UpdateError.INSTALL_FAILED -> R.string.updates_error_install
      UpdateError.INSTALL_CANCELLED -> R.string.updates_error_cancelled
    }
