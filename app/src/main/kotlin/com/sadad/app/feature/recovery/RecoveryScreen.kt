package com.sadad.app.feature.recovery

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sadad.app.R
import com.sadad.app.SadadApplication
import com.sadad.app.ui.components.ConfirmDialog
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

  RecoveryContent(
      status = state.status,
      message = state.message,
      onExport = { passphraseUse = PassphraseUse.EXPORT },
      onImport = { openFile.launch(arrayOf(JSON)) },
      onManifest = { chooseOrigin(OriginUse.MANIFEST) },
      onPermit = { chooseOrigin(OriginUse.PERMIT) },
      onRegisterAgain = { confirmRegister = true },
      modifier = modifier,
  )

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
  Column(
      modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
        text = stringResource(R.string.title_recovery),
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        text = stringResource(R.string.recovery_explanation),
        style = MaterialTheme.typography.bodyMedium,
    )
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
      Text(stringResource(R.string.recovery_save_copy))
    }
    OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
      Text(stringResource(R.string.recovery_import_copy))
    }
    OutlinedButton(onClick = onManifest, modifier = Modifier.fillMaxWidth()) {
      Text(stringResource(R.string.recovery_export_manifest))
    }
    OutlinedButton(onClick = onPermit, modifier = Modifier.fillMaxWidth()) {
      Text(stringResource(R.string.recovery_enter_permit))
    }
    if (status == "revoked") {
      Text(
          text = stringResource(R.string.recovery_register_again_explanation),
          style = MaterialTheme.typography.bodyMedium,
      )
      Button(onClick = onRegisterAgain, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.recovery_register_again))
      }
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
      context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) } != null
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
