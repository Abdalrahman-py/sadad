package com.sadad.app.feature.lock

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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

  /**
   * A fast stand-in for PBKDF2 that parks the thread, so a test controls how long "deriving" takes.
   */
  private fun fakeDerive(onDerive: (String) -> Unit): (String, ByteArray) -> ByteArray =
      { pin, salt ->
        onDerive(pin)
        MessageDigest.getInstance("SHA-256").digest(salt + pin.toByteArray())
      }

  @Test
  fun aPinCheckDoesNotHoldTheWriteLockWhileItDerives() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val armed = AtomicBoolean(false)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    try {
      val derive = fakeDerive {
        if (armed.get()) {
          entered.countDown()
          release.await(10, TimeUnit.SECONDS)
        }
      }
      val lock =
          AppLock({ File(temporary.root, "derive.preferences_pb") }, scope, { 0L }, { 1 }, derive)
      lock.setPin("1234")
      armed.set(true)
      val check = async(Dispatchers.IO) { lock.verifyPin("1234") }
      assertTrue(entered.await(10, TimeUnit.SECONDS))
      assertTrue(withTimeout(5_000) { lock.lockEnabled.first() })
      release.countDown()
      assertEquals(PinResult.Ok, check.await())
    } finally {
      release.countDown()
      scope.cancel()
    }
  }

  @Test
  fun aCorrectPinCannotResetALockoutThatStartedWhileItDerived() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val armed = AtomicBoolean(false)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    try {
      val derive = fakeDerive { pin ->
        if (armed.get() && pin == "1234") {
          entered.countDown()
          release.await(10, TimeUnit.SECONDS)
        }
      }
      val lock =
          AppLock(
              { File(temporary.root, "late.preferences_pb") },
              scope,
              { 100_000L },
              { 1 },
              derive,
          )
      lock.setPin("1234")
      armed.set(true)
      val correct = async(Dispatchers.IO) { lock.verifyPin("1234") }
      assertTrue(entered.await(10, TimeUnit.SECONDS))
      repeat(4) { lock.verifyPin("9999") }
      assertEquals(PinResult.LockedOut(30_000L), lock.verifyPin("9999"))
      release.countDown()
      assertEquals(PinResult.LockedOut(30_000L), correct.await())
      assertEquals(PinResult.LockedOut(30_000L), lock.lockout())
    } finally {
      release.countDown()
      scope.cancel()
    }
  }

  @Test
  fun twoWrongPinsDerivingAtTheSameTimeCountAsTwoFailures() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val armed = AtomicBoolean(false)
    val bothDeriving = CyclicBarrier(2)
    try {
      val derive = fakeDerive { if (armed.get()) bothDeriving.await(10, TimeUnit.SECONDS) }
      val lock =
          AppLock({ File(temporary.root, "pair.preferences_pb") }, scope, { 0L }, { 1 }, derive)
      lock.setPin("1234")
      armed.set(true)
      val results =
          listOf(
                  async(Dispatchers.IO) { lock.verifyPin("9999") },
                  async(Dispatchers.IO) { lock.verifyPin("8888") },
              )
              .awaitAll()
      assertEquals(setOf(PinResult.Wrong(4), PinResult.Wrong(3)), results.toSet())
    } finally {
      scope.cancel()
    }
  }

  @Test
  fun aPinMustHaveExactlyFourDigitsAndOnlyItsVerifierIsPersisted() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val file = File(temporary.root, "lock.preferences_pb")
      val lock = AppLock(file, scope, elapsedRealtime = { 0L }, bootCount = { 1 })
      for (pin in listOf("12", "12345", "1234567890123", "12a4")) {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { lock.setPin(pin) } }
      }
      lock.setPin("4839")
      assertTrue(lock.hasPin())
      assertEquals(PinResult.Ok, lock.verifyPin("4839"))
      assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("4839"))
      lock.clearPin()
      assertFalse(lock.hasPin())
    } finally {
      scope.cancel()
    }
  }

  @Test
  fun theLockDefaultsToOnAndSwitchingItOffSwitchesBiometricOffToo() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val lock = AppLock(File(temporary.root, "flags.preferences_pb"), scope, { 0L }, { 1 })
      assertTrue(lock.lockEnabled.first())
      assertFalse(lock.biometricEnabled.first())
      lock.setBiometricEnabled(true)
      assertTrue(lock.biometricEnabled.first())
      lock.setLockEnabled(false)
      assertFalse(lock.lockEnabled.first())
      assertFalse(lock.biometricEnabled.first())
      lock.setPin("1234")
      assertTrue(lock.lockEnabled.first())
    } finally {
      scope.cancel()
    }
  }

  /**
   * The settings screen collects the three flows once and keeps the collection open. Such a
   * collection sees every toggle; a collection started while a write is in flight is the one that
   * can stay on the old value (see [AppLock.lockEnabled]).
   */
  @Test
  fun anOpenCollectionSeesEveryLockAndBiometricToggle() = runBlocking {
    withTimeout(120_000) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
      try {
        val lock = AppLock(File(temporary.root, "switches.preferences_pb"), scope, { 0L }, { 1 })
        lock.setPin("4839")
        val seen =
            combine(lock.lockEnabled, lock.pinSet, lock.biometricEnabled) { on, pin, biometric ->
                  (on && pin) to biometric
                }
                .stateIn(scope)
        suspend fun expect(step: String, lockOn: Boolean, biometric: Boolean) {
          val want = lockOn to biometric
          val got = withTimeoutOrNull(5_000) { seen.first { it == want } } ?: seen.value
          assertEquals(
              "$step: fresh read lock=${lock.lockEnabled.first()} " +
                  "biometric=${lock.biometricEnabled.first()}",
              want,
              got,
          )
        }
        expect("start", lockOn = true, biometric = false)
        repeat(20) { round ->
          lock.setBiometricEnabled(true)
          expect("round $round biometric on", lockOn = true, biometric = true)
          lock.setLockEnabled(false)
          expect("round $round lock off", lockOn = false, biometric = false)
          lock.setLockEnabled(true)
          expect("round $round lock on", lockOn = true, biometric = false)
        }
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun lockedAtStartAndAfterMoreThan1200MsAwayAndSkipNextLockSuppressesOne() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      var now = 5_000L
      val lock = AppLock(File(temporary.root, "away.preferences_pb"), scope, { now }, { 1 })
      assertTrue(lock.locked.value)
      lock.markUnlocked()
      lock.appStopped()
      now += 1_200
      lock.appStarted()
      assertFalse(lock.locked.value)
      lock.appStopped()
      now += 1_201
      lock.appStarted()
      assertTrue(lock.locked.value)
      lock.markUnlocked()
      lock.skipNextLock()
      lock.appStopped()
      now += 60_000
      lock.appStarted()
      assertFalse(lock.locked.value)
      lock.appStopped()
      now += 2_000
      lock.appStarted()
      assertTrue(lock.locked.value)
    } finally {
      scope.cancel()
    }
  }
}
