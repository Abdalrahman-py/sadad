package com.sadad.app.feature.lock

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val CONTENT = "المحتوى"
private const val LOCKED = "التطبيق مقفل"
private const val HOME = "الرئيسية"
private const val GO = "افتح"
private const val CONTACT = "جهة الاتصال"

@RunWith(RobolectricTestRunner::class)
// A real phone's height, not Robolectric's 320x470 default: the lock board is a full screen with
// its own keypad (no IME), so the keys and the open button must be measured where they live.
@Config(application = UiTestApplication::class, qualifiers = "w360dp-h800dp-xxhdpi")
class LockGateTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val temporary = TemporaryFolder()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val owner = TestOwner()
  private var now = 100_000L
  private var bootReads = 0

  @After fun close() = scope.cancel()

  private fun lockWithPin(): AppLock =
      AppLock(
              File(temporary.root, "lock.preferences_pb"),
              scope,
              { now },
              { 1.also { bootReads++ } },
          )
          .also { runBlocking { it.setPin("1234") } }

  private fun show(
      lock: AppLock,
      hasSession: Boolean = true,
      biometric: BiometricAuth = NoBiometric,
  ) {
    compose.setContent {
      SadadTheme {
        CompositionLocalProvider(LocalLifecycleOwner provides owner) {
          LockGate(lock, hasSession, biometric) { Text(CONTENT) }
        }
      }
    }
  }

  private fun leaveFor(millis: Long) {
    compose.runOnUiThread { owner.send(Lifecycle.Event.ON_STOP) }
    now += millis
    compose.runOnUiThread { owner.send(Lifecycle.Event.ON_START) }
  }

  private fun shown(text: String) =
      compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

  private fun waitFor(text: String) = compose.waitUntil(5_000) { shown(text) }

  @Test
  fun stoppingForTwoSecondsLocksButOneSecondDoesNot() {
    val lock = lockWithPin().also { it.markUnlocked() }
    show(lock)
    compose.onNodeWithText(CONTENT).assertIsDisplayed()

    leaveFor(1_000)
    compose.waitForIdle()
    compose.onNodeWithText(CONTENT).assertIsDisplayed()
    assertFalse(shown(LOCKED))

    leaveFor(2_000)
    waitFor(LOCKED)
    assertFalse(shown(CONTENT))
  }

  @Test
  fun aLockSwitchedOffNeverLocks() {
    val lock = lockWithPin().also { it.markUnlocked() }
    runBlocking { lock.setLockEnabled(false) }
    show(lock)
    leaveFor(2_000)
    waitFor(CONTENT)
    assertFalse(shown(LOCKED))
  }

  @Test
  fun withoutAStoreSessionNothingLocks() {
    show(lockWithPin(), hasSession = false)
    waitFor(CONTENT)
    assertFalse(shown(LOCKED))
  }

  @Test
  fun skipNextLockSuppressesExactlyOneLock() {
    val lock = lockWithPin().also { it.markUnlocked() }
    show(lock)
    lock.skipNextLock()
    leaveFor(2_000)
    compose.waitForIdle()
    compose.onNodeWithText(CONTENT).assertIsDisplayed()

    leaveFor(2_000)
    waitFor(LOCKED)
  }

  @Test
  fun theAppStartsLockedAndOnlyTheRightPinOpensIt() {
    show(lockWithPin())
    waitFor(LOCKED)
    assertFalse(shown(CONTENT))

    typePin("9999")
    waitFor("رمز القفل غير صحيح")
    assertFalse(shown(CONTENT))

    typePin("1234")
    waitFor(CONTENT)
  }

  @Test
  fun threeDigitsDoNotSubmitAndTheFourthOpensAutomatically() {
    show(lockWithPin())
    waitFor(LOCKED)
    typePin("123")
    compose.waitForIdle()
    assertFalse(shown(CONTENT))
    typePin("4")
    waitFor(CONTENT)
  }

  @Test
  fun theLockBoardIsCenteredVertically() {
    show(lockWithPin())
    waitFor(LOCKED)
    val board = compose.onNodeWithTag("lock_board").fetchSemanticsNode().boundsInRoot
    val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
    org.junit.Assert.assertTrue(kotlin.math.abs(board.center.y - root.center.y) < 12f)
  }

  @Test
  fun rapidExtraPressesSubmitOnlyOneFourDigitAttempt() {
    val lock = lockWithPin()
    show(lock)
    waitFor(LOCKED)
    val press =
        compose
            .onNodeWithTag(LockTag.key("9"))
            .fetchSemanticsNode()
            .config[SemanticsActions.OnClick]
            .action!!
    compose.runOnUiThread { repeat(8) { press() } }
    waitFor("رمز القفل غير صحيح")
    assertEquals(PinResult.Wrong(3), runBlocking { lock.verifyPin("9999") })
    typePin("1234")
    waitFor(CONTENT)
  }

  @Test
  @Config(qualifiers = "w360dp-h480dp-xxhdpi")
  fun theKeypadStillScrollsIntoViewOnASmallScreen() {
    show(lockWithPin())
    waitFor(LOCKED)
    compose.onNodeWithTag(LockTag.key("0")).performScrollTo().assertIsDisplayed()
  }

  @Test
  fun aLockoutCountsDownWithoutReadingTheLockAgain() {
    val lock = lockWithPin()
    runBlocking { repeat(5) { lock.verifyPin("9999") } }
    show(lock)
    waitFor("محاولات كثيرة؛ انتظر 30 ثانية.")
    val reads = bootReads
    compose.mainClock.autoAdvance = false
    compose.mainClock.advanceTimeBy(3_000)
    waitFor("محاولات كثيرة؛ انتظر 27 ثانية.")
    assertEquals(reads, bootReads)
  }

  @Test
  fun aBiometricMatchOpensTheApp() {
    val lock = lockWithPin()
    runBlocking { lock.setBiometricEnabled(true) }
    val sensor = Sensor(available = true)
    show(lock, biometric = sensor)
    waitFor(CONTENT)
    assertEquals(1, sensor.prompts)
  }

  @Test
  fun withoutASensorTheBiometricButtonIsAbsent() {
    val lock = lockWithPin()
    runBlocking { lock.setBiometricEnabled(true) }
    show(lock, biometric = Sensor(available = false))
    waitFor(LOCKED)
    assertFalse(shown("استخدام البصمة أو الوجه"))
  }

  @Test
  fun unlockingComesBackToTheScreenTheOwnerWasOn() {
    val lock = lockWithPin().also { it.markUnlocked() }
    compose.setContent {
      SadadTheme {
        CompositionLocalProvider(LocalLifecycleOwner provides owner) {
          // The nav controller is created inside the gated content, exactly as SadadApp is wrapped
          // by the gate: locking removes the NavHost from composition, so only saved state can put
          // the owner back on the screen he was on (#177).
          LockGate(lock, hasSession = true, biometric = NoBiometric) {
            val nav = rememberNavController()
            NavHost(nav, startDestination = HOME) {
              composable(HOME) {
                Text(HOME)
                Text(GO, modifier = Modifier.clickable { nav.navigate(CONTACT) })
              }
              composable(CONTACT) { Text(CONTACT) }
            }
          }
        }
      }
    }
    waitFor(HOME)
    compose.onNodeWithText(GO).performClick()
    waitFor(CONTACT)

    leaveFor(2_000)
    waitFor(LOCKED)
    assertFalse(shown(CONTACT))

    lock.markUnlocked()

    waitFor(CONTACT)
    assertFalse(shown(HOME))
  }

  private object NoBiometric : BiometricAuth {
    override fun available() = false

    override fun authenticate(onSuccess: () -> Unit) = Unit
  }

  private class Sensor(private val available: Boolean) : BiometricAuth {
    var prompts = 0

    override fun available() = available

    override fun authenticate(onSuccess: () -> Unit) {
      prompts++
      onSuccess()
    }
  }

  private class TestOwner : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle
      get() = registry

    init {
      send(Lifecycle.Event.ON_CREATE)
      send(Lifecycle.Event.ON_START)
    }

    fun send(event: Lifecycle.Event) = registry.handleLifecycleEvent(event)
  }

  /** The PIN is typed on the lock's own keypad, one key at a time. */
  private fun typePin(pin: String) = pin.forEach {
    compose.onNodeWithTag(LockTag.key(it.toString())).performClick()
  }
}
