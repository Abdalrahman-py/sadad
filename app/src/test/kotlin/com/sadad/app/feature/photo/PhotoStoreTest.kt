package com.sadad.app.feature.photo

import android.content.Context
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.ui.UiTestApplication
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class PhotoStoreTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun saveProducesASquareAndDeleteRemovesOnlyThatStoresSelectedPhoto() = runBlocking {
    val photos = PhotoStore(context)
    val id = UUID.randomUUID().toString()
    val source = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
    photos.save(id, PhotoKind.STORE, source)
    photos.save(id, PhotoKind.ACCOUNT, source)
    val stored = checkNotNull(photos.load(id, PhotoKind.STORE))
    assertEquals(1024, stored.width)
    assertEquals(1024, stored.height)
    assertNull(photos.load("another-store", PhotoKind.STORE))
    photos.delete(id, PhotoKind.STORE)
    assertNull(photos.load(id, PhotoKind.STORE))
    assertNotNull(photos.load(id, PhotoKind.ACCOUNT))
    photos.delete(id, PhotoKind.ACCOUNT)
  }

  @Test
  fun aJpegWithExifOrientationSixLoadsAsAPortraitAndLargeSourcesAreSampled() = runBlocking<Unit> {
    val photos = PhotoStore(context)
    val file = File(context.cacheDir, "oriented-${UUID.randomUUID()}.jpg")
    val source = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888)
    file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    ExifInterface(file.path).apply {
      setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
      saveAttributes()
    }
    val loaded = photos.load(Uri.fromFile(file))
    assertEquals(1000, loaded.width)
    assertEquals(1500, loaded.height)
    file.delete()
  }
}
