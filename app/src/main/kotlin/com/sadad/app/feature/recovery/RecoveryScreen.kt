package com.sadad.app.feature.recovery

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sadad.app.R
import com.sadad.app.SadadApplication
import com.sadad.app.ui.components.ConfirmDialog
import com.sadad.app.ui.components.SadadButton
import com.sadad.app.ui.components.SadadButtonStyle
import com.sadad.app.ui.components.ScreenHeader
import com.sadad.app.ui.theme.sadadPalette
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val JSON = "application/json"
private const val RECOVERY_FILE = "Sadad-Recovery.json"
private const val MANIFEST_FILE = "Sadad-Approval-Manifest.json"
private const val MAX_RECOVERY_BYTES = 8 * 1024 * 1024

private enum class PassphraseUse {
  EXPORT,
  IMPORT,
}

private enum class OriginUse {
  MANIFEST,
  PERMIT,
}

@Composable
fun RecoveryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecoveryViewModel = recoveryViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val coroutine = rememberCoroutineScope()

  val saved = stringResource(R.string.recovery_saved)
  val saveFailed = stringResource(R.string.recovery_save_failed)
  val readFailed = stringResource(R.string.recovery_read_failed)
  val imported = stringResource(R.string.recovery_imported)
  val noNewEntries = stringResource(R.string.recovery_import_none)
  val noOrigins = stringResource(R.string.recovery_no_origins)

  var passphraseUse by remember { mutableStateOf<PassphraseUse?>(null) }
  var importBytes by remember { mutableStateOf<ByteArray?>(null) }
  var originUse by remember { mutableStateOf<OriginUse?>(null) }
  var origins by remember { mutableStateOf<List<String>>(emptyList()) }
  var permitOrigin by remember { mutableStateOf<String?>(null) }
  var confirmRegister by remember { mutableStateOf(false) }
  var fileBytes by remember { mutableStateOf<ByteArray?>(null) }

  val saveFile =
      rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(JSON)) { uri ->
        val bytes = fileBytes
        fileBytes = null
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        coroutine.launch {
          val written = withContext(Dispatchers.IO) { write(context, uri, bytes) }
          viewModel.showMessage(if (written) saved else saveFailed)
        }
      }

  val openFile =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutine.launch {
          val bytes = withContext(Dispatchers.IO) { read(context, uri) }
          if (bytes == null) viewModel.showMessage(readFailed)
          else {
            importBytes = bytes
            passphraseUse = PassphraseUse.IMPORT
          }
        }
      }

  val chooseOrigin: (OriginUse) -> Unit = { use ->
    coroutine.launch {
      val found = viewModel.origins()
      if (found.isEmpty()) viewModel.showMessage(noOrigins)
      else {
        origins = found
        originUse = use
      }
    }
  }

  Column(modifier.fillMaxSize()) {
    ScreenHeader(stringResource(R.string.title_recovery), onBack = onBack)
    RecoveryContent(
        status = state.status,
        message = state.message,
        onExport = { passphraseUse = PassphraseUse.EXPORT },
        onImport = { openFile.launch(arrayOf(JSON)) },
        onManifest = { chooseOrigin(OriginUse.MANIFEST) },
        onPermit = { chooseOrigin(OriginUse.PERMIT) },
        onRegisterAgain = { confirmRegister = true },
        modifier = Modifier.weight(1f),
    )
  }

  passphraseUse?.let { use ->
    val exporting = use == PassphraseUse.EXPORT
    PassphraseDialog(
        title =
            stringResource(
                if (exporting) R.string.recovery_export_title else R.string.recovery_import_title
            ),
        message =
            stringResource(
                if (exporting) R.string.recovery_export_message
                else R.string.recovery_import_message
            ),
        confirmText =
            stringResource(if (exporting) R.string.recovery_save else R.string.recovery_import),
        onDismiss = { passphraseUse = null },
        onConfirm = { passphrase ->
          passphraseUse = null
          coroutine.launch {
            if (exporting) {
              val bytes = viewModel.export(passphrase)
              if (bytes != null) {
                fileBytes = bytes
                saveFile.launch(RECOVERY_FILE)
              }
            } else {
              val bytes = importBytes
              importBytes = null
              if (bytes != null) {
                val added = viewModel.import(bytes, passphrase)
                if (added != null) {
                  viewModel.showMessage(if (added > 0) imported else noNewEntries)
                }
              }
            }
          }
        },
    )
  }

  if (originUse != null) {
    AlertDialog(
        onDismissRequest = { originUse = null },
        title = { Text(stringResource(R.string.recovery_manifest_title)) },
        text = {
          Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.recovery_manifest_message))
            origins.forEach { origin ->
              TextButton(
                  onClick = {
                    val use = originUse
                    originUse = null
                    if (use == OriginUse.MANIFEST) {
                      coroutine.launch {
                        val bytes = viewModel.manifest(origin)
                        if (bytes != null) {
                          fileBytes = bytes
                          saveFile.launch(MANIFEST_FILE)
                        }
                      }
                    } else {
                      permitOrigin = origin
                    }
                  }
              ) {
                Text(origin)
              }
            }
          }
        },
        confirmButton = {},
        dismissButton = {
          TextButton(onClick = { originUse = null }) {
            Text(stringResource(R.string.recovery_cancel))
          }
        },
    )
  }

  permitOrigin?.let { origin ->
    var permit by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { permitOrigin = null },
        title = { Text(stringResource(R.string.recovery_permit_title)) },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(origin)
            OutlinedTextField(
                value = permit,
                onValueChange = { permit = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.recovery_permit_hint)) },
            )
          }
        },
        confirmButton = {
          TextButton(
              onClick = {
                val id = permit.trim()
                permitOrigin = null
                viewModel.enterPermit(origin, id)
              }
          ) {
            Text(stringResource(R.string.recovery_save))
          }
        },
        dismissButton = {
          TextButton(onClick = { permitOrigin = null }) {
            Text(stringResource(R.string.recovery_cancel))
          }
        },
    )
  }

  if (confirmRegister) {
    ConfirmDialog(
        title = stringResource(R.string.recovery_register_again),
        message = stringResource(R.string.recovery_register_again_explanation),
        confirmText = stringResource(R.string.recovery_register_confirm),
        dismissText = stringResource(R.string.recovery_cancel),
        onConfirm = {
          confirmRegister = false
          viewModel.registerAgain()
        },
        onDismiss = { confirmRegister = false },
    )
  }
}

/**
 * The Recovery board: what the screen is for, «أنتقل إلى هاتف جديد» as two numbered steps, the
 * admin's steps folded away, and registering again (enabled only once the device is revoked).
 */
@Composable
fun RecoveryContent(
    status: String?,
    modifier: Modifier = Modifier,
    message: String? = null,
    onExport: () -> Unit = {},
    onImport: () -> Unit = {},
    onManifest: () -> Unit = {},
    onPermit: () -> Unit = {},
    onRegisterAgain: () -> Unit = {},
) {
  val p = sadadPalette
  var adminOpen by rememberSaveable { mutableStateOf(false) }
  Column(
      modifier =
          modifier
              .fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(start = 18.dp, end = 18.dp, bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Row(
        Modifier.fillMaxWidth()
            .background(p.soft, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
          painterResource(R.drawable.ic_phone),
          null,
          tint = if (p.isDark) p.accent else p.primary,
          modifier = Modifier.size(20.dp),
      )
      Text(
          stringResource(R.string.recovery_explanation),
          color = p.text,
          fontSize = 14.sp,
          lineHeight = 21.sp,
      )
    }
    AnimatedVisibility(message != null, enter = fadeIn(), exit = fadeOut()) {
      Text(
          message.orEmpty(),
          color = p.text,
          fontSize = 14.sp,
          lineHeight = 21.sp,
          modifier =
              Modifier.fillMaxWidth()
                  .background(p.creditSoft, RoundedCornerShape(13.dp))
                  .padding(horizontal = 14.dp, vertical = 12.dp)
                  .semantics { liveRegion = LiveRegionMode.Polite },
      )
    }
    Column(
        Modifier.fillMaxWidth()
            .background(p.card, RoundedCornerShape(18.dp))
            .border(1.dp, p.line, RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Text(
          stringResource(R.string.recovery_move_title),
          color = p.text,
          fontSize = 17.sp,
          lineHeight = 24.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.semantics { heading() },
      )
      NumberedStep(
          number = 1,
          first = true,
          title = stringResource(R.string.recovery_step_save_title),
          detail = stringResource(R.string.recovery_step_save_detail),
          action = stringResource(R.string.recovery_save_copy),
          onAction = onExport,
      )
      NumberedStep(
          number = 2,
          first = false,
          title = stringResource(R.string.recovery_step_import_title),
          detail = stringResource(R.string.recovery_step_import_detail),
          action = stringResource(R.string.recovery_import_copy),
          onAction = onImport,
      )
    }
    Column(
        Modifier.fillMaxWidth()
            .background(p.card, RoundedCornerShape(13.dp))
            .border(1.dp, p.line, RoundedCornerShape(13.dp))
            .animateContentSize(tween(220)),
    ) {
      val turn by animateFloatAsState(if (adminOpen) 180f else 0f, tween(220), label = "chevron")
      Row(
          Modifier.fillMaxWidth()
              .heightIn(min = 56.dp)
              .toggleable(value = adminOpen, role = Role.Button) { adminOpen = it }
              .padding(horizontal = 14.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
            stringResource(R.string.recovery_admin_title),
            color = p.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painterResource(R.drawable.ic_chevron_down_wide),
            null,
            tint = p.muted,
            modifier = Modifier.size(20.dp).rotate(turn),
        )
      }
      if (adminOpen) {
        Column(
            Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
              stringResource(R.string.recovery_admin_detail),
              color = p.muted,
              fontSize = 13.sp,
              lineHeight = 20.sp,
          )
          SadadButton(
              text = stringResource(R.string.recovery_export_manifest),
              onClick = onManifest,
              style = SadadButtonStyle.SOFT,
              height = 44.dp,
              modifier = Modifier.fillMaxWidth(),
          )
          SadadButton(
              text = stringResource(R.string.recovery_enter_permit),
              onClick = onPermit,
              style = SadadButtonStyle.SOFT,
              height = 44.dp,
              modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    }
    val revoked = status == "revoked"
    Column(
        Modifier.fillMaxWidth().border(1.5.dp, p.line, RoundedCornerShape(13.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
          stringResource(R.string.recovery_register_title),
          color = p.text,
          fontSize = 15.sp,
          lineHeight = 22.sp,
          fontWeight = FontWeight.Bold,
      )
      Text(
          stringResource(R.string.recovery_register_again_explanation),
          color = p.muted,
          fontSize = 13.sp,
          lineHeight = 20.sp,
      )
      SadadButton(
          text =
              stringResource(
                  if (revoked) R.string.recovery_register_again
                  else R.string.recovery_register_not_needed
              ),
          onClick = onRegisterAgain,
          enabled = revoked,
          height = 44.dp,
          modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

/** A numbered step: the green 1 is where to start, the next ones sit on `surface`. */
@Composable
private fun NumberedStep(
    number: Int,
    first: Boolean,
    title: String,
    detail: String,
    action: String,
    onAction: () -> Unit,
) {
  val p = sadadPalette
  val link = if (p.isDark) p.accent else p.primary
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Box(
        // A minimum, not a fixed 28dp: at the largest font the digit is taller and wider than the
        // circle, and a fixed size squeezed it to a 1px sliver instead of letting the badge grow.
        Modifier.sizeIn(minWidth = 28.dp, minHeight = 28.dp)
            .background(if (first) p.primary else p.surface, CircleShape)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
      Text(
          "$number",
          color = if (first) Color.White else link,
          fontSize = 14.sp,
          fontWeight = FontWeight.Bold,
          softWrap = false,
          maxLines = 1,
      )
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
          title,
          color = p.text,
          fontSize = 15.sp,
          lineHeight = 22.sp,
          fontWeight = FontWeight.Bold,
      )
      Text(detail, color = p.muted, fontSize = 13.sp, lineHeight = 20.sp)
      SadadButton(
          text = action,
          onClick = onAction,
          style = if (first) SadadButtonStyle.PRIMARY else SadadButtonStyle.SOFT,
          height = 44.dp,
          modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

@Composable
private fun PassphraseDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
  // Deliberately not rememberSaveable: the passphrase must never reach SavedStateHandle.
  var text by remember { mutableStateOf("") }
  var tooShort by remember { mutableStateOf(false) }
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(title) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(message)
          OutlinedTextField(
              value = text,
              onValueChange = {
                text = it
                tooShort = false
              },
              singleLine = true,
              visualTransformation = PasswordVisualTransformation(),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
              placeholder = { Text(stringResource(R.string.recovery_passphrase_hint)) },
              isError = tooShort,
              supportingText =
                  if (tooShort) {
                    { Text(stringResource(R.string.recovery_passphrase_short)) }
                  } else null,
          )
        }
      },
      confirmButton = {
        TextButton(
            onClick = {
              if (text.length < 12) tooShort = true
              else {
                val passphrase = text.toCharArray()
                text = ""
                onConfirm(passphrase)
              }
            }
        ) {
          Text(confirmText)
        }
      },
      dismissButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.recovery_cancel)) }
      },
  )
}

@Composable
private fun recoveryViewModel(): RecoveryViewModel =
    viewModel(
        factory =
            viewModelFactory {
              initializer {
                val app = this[APPLICATION_KEY] as SadadApplication
                RecoveryViewModel(app.graph, app)
              }
            }
    )

private fun write(context: Context, uri: Uri, bytes: ByteArray): Boolean =
    try {
      context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null
    } catch (_: Exception) {
      false
    }

private fun read(context: Context, uri: Uri): ByteArray? =
    try {
      context.contentResolver.openInputStream(uri)?.use { input ->
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
          val count = input.read(buffer)
          if (count < 0) break
          if (bytes.size() + count > MAX_RECOVERY_BYTES) return null
          bytes.write(buffer, 0, count)
        }
        bytes.toByteArray()
      }
    } catch (_: Exception) {
      null
    }
