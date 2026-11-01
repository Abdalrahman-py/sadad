package com.sadad.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `AppGraph.create` against the real Keystore, which the JVM tests cannot reach (see KeystoreTest).
 * A human runs it over adb: `./gradlew connectedDebugAndroidTest`. It only reads, so a phone with a
 * signed-in store keeps its data.
 */
@RunWith(AndroidJUnit4::class)
class AppGraphCreateTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test
  fun createUnwrapsTheDataKeyReadsTheAccountsAndOpensNoStore() = runBlocking {
    val graph = AppGraph.create(context)

    assertNotNull(graph.accounts.file())
    assertNull(graph.current.value)
  }
}
