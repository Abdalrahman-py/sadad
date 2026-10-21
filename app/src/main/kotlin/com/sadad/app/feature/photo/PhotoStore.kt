package com.sadad.app.feature.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class PhotoKind(val suffix: String) {
  STORE("store"),
  ACCOUNT("account"),
}

/** Two small photos per store. Every decode, EXIF read and file write stays off the UI thread. */
class PhotoStore(context: Context) {
  private val app = context.applicationContext
  private val directory = File(app.filesDir, "photos")
  private val mutex = Mutex()
  private val revision = MutableStateFlow(0L)
  val changes = revision.asStateFlow()

  suspend fun load(storeUserId: String, kind: PhotoKind): Bitmap? =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          val file = photoFile(storeUserId, kind)
          if (!file.exists()) null else decode { file.inputStream() }
        }
      }

  /** A picked document is sampled before allocating its pixels, then oriented from EXIF. */
  suspend fun load(uri: Uri): Bitmap =
      withContext(Dispatchers.IO) {
        decode { app.contentResolver.openInputStream(uri) }
      }

  suspend fun save(storeUserId: String, kind: PhotoKind, bitmap: Bitmap) =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          if (!directory.exists() && !directory.mkdirs()) throw IOException("photo directory")
          val output = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
          try {
            val side = minOf(bitmap.width, bitmap.height).toFloat()
            val left = (bitmap.width - side) / 2f
            val top = (bitmap.height - side) / 2f
            Canvas(output)
                .drawBitmap(
                    bitmap,
                    RectF(left, top, left + side, top + side).let {
                      android.graphics.Rect(
                          it.left.toInt(),
                          it.top.toInt(),
                          it.right.toInt(),
                          it.bottom.toInt(),
                      )
                    },
                    RectF(0f, 0f, 1024f, 1024f),
                    Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
                )
            val atomic = AtomicFile(photoFile(storeUserId, kind))
            val stream = atomic.startWrite()
            try {
              if (!output.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw IOException("photo encoding")
              }
              atomic.finishWrite(stream)
            } catch (error: Throwable) {
              atomic.failWrite(stream)
              throw error
            }
            revision.update { it + 1 }
          } finally {
            output.recycle()
          }
        }
      }

  suspend fun delete(storeUserId: String, kind: PhotoKind) =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          val file = photoFile(storeUserId, kind)
          if (file.exists() && !file.delete()) throw IOException("photo deletion")
          revision.update { it + 1 }
        }
      }

  private fun photoFile(storeUserId: String, kind: PhotoKind): File {
    require(storeUserId.matches(Regex("[A-Za-z0-9_-]+")))
    return File(directory, "$storeUserId-${kind.suffix}.png")
  }

  private fun decode(open: () -> InputStream?): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open().use { input ->
      if (input == null) throw IOException("photo unavailable")
      BitmapFactory.decodeStream(input, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("invalid image")
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    val options =
        BitmapFactory.Options().apply {
          inSampleSize = sample
          inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    val bitmap =
        open().use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("invalid image")
    val orientation =
        open().use {
          if (it == null) throw IOException("photo unavailable")
          ExifInterface(it)
              .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    val transform =
        Matrix().apply {
          when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
              setRotate(90f)
              postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
              setRotate(-90f)
              postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
          }
        }
    if (transform.isIdentity) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true).also {
      if (it !== bitmap) bitmap.recycle()
    }
  }

  companion object {
    @Volatile private var instance: PhotoStore? = null

    fun get(context: Context): PhotoStore =
        instance
            ?: synchronized(this) {
              instance ?: PhotoStore(context).also { instance = it }
            }
  }
}
