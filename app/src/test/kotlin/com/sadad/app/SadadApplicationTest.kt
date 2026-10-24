package com.sadad.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SadadApplicationTest {
  private fun violation(vararg classes: String) =
      RuntimeException().apply {
        stackTrace = classes.map { StackTraceElement(it, "run", "F.kt", 1) }.toTypedArray()
      }

  @Test
  fun `a vendor read under our onCreate is not ours`() {
    val miui =
        violation(
            "java.io.File",
            "miui.util.font.FontSettings",
            "androidx.core.app.ComponentActivity",
            "com.sadad.app.MainActivity",
            "android.app.ActivityThread",
        )
    assertFalse(blockingCallIsOurs(miui))
  }

  @Test
  fun `a read from our code through the platform or androidx is ours`() {
    val ours =
        violation(
            "java.io.File",
            "androidx.datastore.core.Foo",
            "com.sadad.app.data.Store",
            "android.os.Handler",
        )
    assertTrue(blockingCallIsOurs(ours))
  }
}
