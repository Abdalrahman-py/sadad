package com.sadad.app.feature.lock

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppLockTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test
  fun aPinMustHaveFourToTwelveDigitsAndOnlyItsVerifierIsPersisted() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val file = File(temporary.root, "lock.preferences_pb")
      val lock = AppLock(file, scope, elapsedRealtime = { 0L }, bootCount = { 1 })
      for (pin in listOf("12", "1234567890123", "12a4")) {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { lock.setPin(pin) } }
      }
      lock.setPin("483927")
      assertTrue(lock.hasPin())
      assertEquals(PinResult.Ok, lock.verifyPin("483927"))
      assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("483927"))
      lock.clearPin()
      assertFalse(lock.hasPin())
    } finally {
      scope.cancel()
    }
  }
}
