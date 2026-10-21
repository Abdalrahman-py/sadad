package com.sadad.app.feature.photo

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.sadad.app.R
import com.sadad.app.feature.lock.AppLock
import java.io.IOException

/** Shared by the account menu and the store-photo settings row. */
@Composable
fun PhotoEditor(
    storeUserId: String,
    kind: PhotoKind,
    onDismiss: () -> Unit,
    currentStoreUserId: () -> String? = { storeUserId },
) {
  val context = LocalContext.current
  val photos = remember(context) { PhotoStore.get(context) }
  val lock = remember(context) { AppLock.get(context) }
  var picked by rememberSaveable(storeUserId, kind) { mutableStateOf<String?>(null) }
  var pickerRequested by rememberSaveable(storeUserId, kind) { mutableStateOf(false) }
  var pickerStore by rememberSaveable { mutableStateOf<String?>(null) }
  var bitmap by remember(storeUserId, kind) { mutableStateOf<Bitmap?>(null) }
  var failed by remember { mutableStateOf(false) }
  val picker =
      rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (pickerStore != currentStoreUserId()) {
          onDismiss()
        } else if (uri == null) {
          if (bitmap == null) onDismiss()
        } else {
          picked = uri.toString()
        }
      }
  val launch = {
    pickerStore = storeUserId
    pickerRequested = true
    lock.skipNextLock()
    try {
      picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    } catch (_: ActivityNotFoundException) {
      lock.appStarted() // A launch that never left the app must not skip a later real background.
      failed = true
    }
  }
  LaunchedEffect(storeUserId, kind, picked) {
    failed = false
    try {
      bitmap = picked?.let { photos.load(Uri.parse(it)) } ?: photos.load(storeUserId, kind)
      if (bitmap == null && !pickerRequested) launch()
    } catch (_: IOException) {
      failed = true
    }
  }
  val image = bitmap
  DisposableEffect(image) { onDispose { image?.recycle() } }
  if (image != null) {
    PhotoEditorDialog(
        bitmap = image,
        onSave = {
          if (currentStoreUserId() != storeUserId) throw IOException("store changed")
          photos.save(storeUserId, kind, it)
        },
        onDelete = {
          if (currentStoreUserId() != storeUserId) throw IOException("store changed")
          photos.delete(storeUserId, kind)
        },
        onNewPhoto = launch,
        onDismiss = onDismiss,
    )
  }
  if (failed) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(R.string.photo_failed)) },
        confirmButton = {
          TextButton(onClick = onDismiss) { Text(stringResource(R.string.photo_cancel)) }
        },
    )
  }
}
