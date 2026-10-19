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
  fun fiveFailuresStartAnEscalatingMonotonicLockoutThatARebootCannotShorten() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var now = 100_000L
    var boot = 1
    try {
      val lock = AppLock(File(temporary.root, "attempts.preferences_pb"), scope, { now }, { boot })
      lock.setPin("1234")
      for (remaining in 4 downTo 1) {
        assertEquals(PinResult.Wrong(remaining), lock.verifyPin("9999"))
      }
      assertEquals(PinResult.LockedOut(30_000L), lock.verifyPin("9999"))
      assertEquals(PinResult.LockedOut(30_000L), lock.verifyPin("1234"))
      now += 5_000
      assertEquals(PinResult.LockedOut(25_000L), lock.lockout())
      boot = 2
      now = 1_000
      assertEquals(PinResult.LockedOut(30_000L), lock.lockout())
      now += 29_999
      assertEquals(PinResult.LockedOut(1L), lock.verifyPin("1234"))
      now++
      for (delay in listOf(60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L)) {
        repeat(4) { lock.verifyPin("9999") }
        assertEquals(PinResult.LockedOut(delay), lock.verifyPin("9999"))
        now += delay
      }
      assertEquals(PinResult.Ok, lock.verifyPin("1234"))
      repeat(4) { lock.verifyPin("9999") }
      assertEquals(PinResult.LockedOut(30_000L), lock.verifyPin("9999"))
    } finally {
      scope.cancel()
    }
  }

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
