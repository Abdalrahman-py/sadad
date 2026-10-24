package com.sadad.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import java.io.File
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Smoke test for the Phase 3 build plumbing: Room/KSP and Robolectric on the JVM, the errors
// asset, the lease-key BuildConfig fields and the shared contract fixtures.
@RunWith(RobolectricTestRunner::class)
class PlumbingTest {
  @Test
  fun shipsTheWriterFacingErrorsAsset() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val json = context.assets.open("errors.json").use { it.readBytes().decodeToString() }
    val errors: Map<String, String> = Json.decodeFromString(json)
    val message = errors.getValue("device_limit_reached")
    assertTrue("blank message for device_limit_reached", message.isNotBlank())
    // Arabic script, so the app ships a message a writer can read.
    assertTrue(
        "expected an Arabic message, got '$message'",
        message.any { it.code in 0x0600..0x06FF },
    )
  }

  @Test
  fun leaseKeyDefaultsToTheProjectKey() {
    assertEquals("w1NJG8ul9K-a0cPomIp5m0qPFBlQXoWTfd6504pGzIY", BuildConfig.LEASE_PUBLIC_KEY_X)
    assertEquals("g9oiLjzP9j6aZMAk_X_Q45PKvEc5vYPRAfsfbFFdvSk", BuildConfig.LEASE_PUBLIC_KEY_Y)
  }

  @Test
  fun unitTestsSeeTheSharedContractFixtures() {
    val fixtures = File(System.getProperty("sadad.fixtures")!!)
    assertTrue(
        "missing ${File(fixtures, "mobile/proof.json")}",
        File(fixtures, "mobile/proof.json").isFile,
    )
  }

  @Test
  fun turbineAndCoroutinesTestRunOnTheClasspath() = runTest {
    flowOf(1).test {
      assertEquals(1, awaitItem())
      awaitComplete()
    }
  }
}
