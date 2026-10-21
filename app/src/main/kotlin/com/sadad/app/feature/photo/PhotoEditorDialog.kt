package com.sadad.app.feature.photo

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.sadad.app.R
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The preview and exported bitmap use exactly the same source-to-square transform. */
@Composable
fun PhotoEditorDialog(
    bitmap: Bitmap,
    onSave: suspend (Bitmap) -> Unit,
    onDelete: suspend () -> Unit,
    onNewPhoto: () -> Unit,
    onDismiss: () -> Unit,
) {
  val crop = remember(bitmap) { CropState(bitmap.width, bitmap.height) }
  var revision by remember(bitmap) { mutableIntStateOf(0) }
  var busy by remember { mutableStateOf(false) }
  var failed by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val description = stringResource(R.string.photo_preview)
  // Reading revision subscribes both the slider and canvas to the plain Kotlin crop state.
  val rect = remember(crop, revision) { crop.exportRect() }
  val zoom = crop.zoom
  val rotation = crop.rotation
  AlertDialog(
      onDismissRequest = { if (!busy) onDismiss() },
      title = { Text(stringResource(R.string.photo_title)) },
      text = {
        Column {
          Canvas(
              Modifier.fillMaxWidth()
                  .aspectRatio(1f)
                  .clipToBounds()
                  .semantics { contentDescription = description }
                  .pointerInput(crop, busy) {
                    detectTransformGestures { _, pan, scale, _ ->
                      if (!busy) {
                        crop.setZoom(crop.zoom * scale)
                        val sourcePerPixel =
                            (crop.exportRect().right - crop.exportRect().left) / size.width
                        crop.drag(pan.x * sourcePerPixel, pan.y * sourcePerPixel)
                        revision++
                      }
                    }
                  }
          ) {
            drawPhotoCrop(drawContext.canvas.nativeCanvas, bitmap, rotation, rect, size.width)
          }
          Text(stringResource(R.string.photo_help))
          Slider(
              value = zoom,
              onValueChange = {
                crop.setZoom(it)
                revision++
              },
              valueRange = 1f..4f,
              enabled = !busy,
              modifier =
                  Modifier.semantics {
                    contentDescription = description
                  },
          )
          Row {
            TextButton(
                onClick = {
                  crop.rotate()
                  revision++
                },
                enabled = !busy,
            ) {
              Text(stringResource(R.string.photo_rotate))
            }
            TextButton(
                onClick = {
                  crop.center()
                  revision++
                },
                enabled = !busy,
            ) {
              Text(stringResource(R.string.photo_center))
            }
            TextButton(onClick = onNewPhoto, enabled = !busy) {
              Text(stringResource(R.string.photo_new))
            }
          }
          if (failed)
              Text(stringResource(R.string.photo_failed), color = MaterialTheme.colorScheme.error)
        }
      },
      confirmButton = {
        TextButton(
            enabled = !busy,
            onClick = {
              busy = true
              failed = false
              val rotation = crop.rotation
              val selection = crop.exportRect()
              scope.launch {
                try {
                  val output =
                      withContext(Dispatchers.IO) {
                        Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888).also {
                          drawPhotoCrop(AndroidCanvas(it), bitmap, rotation, selection, 1024f)
                        }
                      }
                  try {
                    onSave(output)
                  } finally {
                    output.recycle()
                  }
                  onDismiss()
                } catch (_: IOException) {
                  failed = true
                } finally {
                  busy = false
                }
              }
            },
        ) {
          Text(stringResource(R.string.photo_save))
        }
      },
      dismissButton = {
        Row {
          TextButton(onClick = onDismiss, enabled = !busy) {
            Text(stringResource(R.string.photo_cancel))
          }
          TextButton(
              enabled = !busy,
              onClick = {
                busy = true
                failed = false
                scope.launch {
                  try {
                    onDelete()
                    onDismiss()
                  } catch (_: IOException) {
                    failed = true
                  } finally {
                    busy = false
                  }
                }
              },
          ) {
            Text(stringResource(R.string.photo_delete))
          }
        }
      },
  )
}

internal fun drawPhotoCrop(
    canvas: AndroidCanvas,
    bitmap: Bitmap,
    rotation: Int,
    rect: CropRect,
    side: Float,
) {
  val matrix = Matrix().apply { setRotate(rotation.toFloat()) }
  val bounds = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
  matrix.mapRect(bounds)
  matrix.postTranslate(-bounds.left - rect.left, -bounds.top - rect.top)
  val scale = side / (rect.right - rect.left)
  matrix.postScale(scale, scale)
  canvas.drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
}
