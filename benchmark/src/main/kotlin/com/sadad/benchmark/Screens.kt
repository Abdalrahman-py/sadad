package com.sadad.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

/** The benchmark build of :app (`benchmark` build type, no application id suffix). */
const val PACKAGE = "com.sadad.app"

private const val WAIT_MS = 15_000L

// The texts below are copied from app/src/main/res/values (Compose test tags are not visible to
// UiAutomator, and the app's other sources are out of this module's scope). If a string changes the
// benchmark fails with a clear message; update it here.
private const val NAV_CONTACTS = "الأشخاص"
private const val NAV_SETTINGS = "الإعدادات"
private const val CONTACTS_EMPTY = "لا يوجد زبائن مسجلون بعد"
private const val RECORD_DEBT = "＋ تسجيل دين"
private const val SAVE_DEBT = "حفظ الدين"
private const val CONFIRM_DEBT = "تأكيد تسجيل الدين"
private const val DOWNLOAD_LATEST = "تحميل أحدث السجلات"
private const val SYNC_RUNNING = "جارٍ تحديث السجلات…"
private const val SYNC_IDLE = "السجلات محدّثة"

/** Profile used for the comparison pairs: with the Baseline Profile and without any. */
val profiled = CompilationMode.Partial(BaselineProfileMode.Require)
val unprofiled = CompilationMode.None()

private fun precondition(): Nothing =
    error(
        "The bottom bar did not appear. The phone must have the benchmark build of :app installed, " +
            "signed into the seeded store (ticket 62), approved, synced and unlocked. " +
            "Do that by hand once, then rerun."
    )

/**
 * Starts the app (cold or warm, as the caller set up) and fails unless it reached a signed-in
 * store.
 */
fun MacrobenchmarkScope.startToHome() {
  startActivityAndWait()
  if (!device.wait(Until.hasObject(By.text(NAV_CONTACTS)), WAIT_MS)) precondition()
}

fun MacrobenchmarkScope.openContacts() {
  device.findObject(By.text(NAV_CONTACTS)).click()
  if (!device.wait(Until.hasObject(By.scrollable(true)), WAIT_MS)) {
    error("The Contacts list did not appear.")
  }
  if (device.hasObject(By.text(CONTACTS_EMPTY))) {
    error("The Contacts list is empty. Seed the store first (ticket 62).")
  }
}

/** Flings down and up alternately, so a short list still moves on every fling. */
fun MacrobenchmarkScope.flingContacts(times: Int) {
  val list = device.findObject(By.scrollable(true)) ?: error("No scrollable Contacts list.")
  list.setGestureMargin(device.displayWidth / 5)
  repeat(times) { index ->
    list.fling(if (index % 2 == 0) Direction.DOWN else Direction.UP)
    device.waitForIdle()
  }
}

fun MacrobenchmarkScope.openFirstContact() {
  val list = device.findObject(By.scrollable(true)) ?: error("No scrollable Contacts list.")
  val row = list.findObject(By.clickable(true)) ?: error("The Contacts list has no row.")
  row.click()
  if (!device.wait(Until.hasObject(By.text(RECORD_DEBT)), WAIT_MS)) {
    error("The Contact detail did not open.")
  }
}

fun MacrobenchmarkScope.openDebtForm() {
  device.findObject(By.text(RECORD_DEBT)).click()
  if (!device.wait(Until.hasObject(By.text(SAVE_DEBT)), WAIT_MS))
      error("The Debt form did not open.")
}

/** Enters 10 and confirms the review; the app's `save_debt` span is what gets measured. */
fun MacrobenchmarkScope.saveTenShekelDebt() {
  openDebtForm()
  device.findObject(By.clazz("android.widget.EditText")).text = "10"
  device.findObject(By.text(SAVE_DEBT)).click()
  if (!device.wait(Until.hasObject(By.text(CONFIRM_DEBT)), WAIT_MS)) error("No review dialog.")
  device.findObject(By.text(CONFIRM_DEBT)).click()
  device.waitForIdle()
}

fun MacrobenchmarkScope.openSettings() {
  device.findObject(By.text(NAV_SETTINGS)).click()
  if (!device.wait(Until.hasObject(By.text(DOWNLOAD_LATEST)), WAIT_MS)) error("No Settings screen.")
}

/**
 * One idle pass from Settings. Waits up to 2 s for the "running" status, then for it to go away, so
 * the `sync_pass` span has closed before the iteration ends. A pass faster than 2 s that never
 * shows "running" is still covered: the wait itself keeps the iteration open for 2 s.
 */
fun MacrobenchmarkScope.syncOnce() {
  device.findObject(By.text(DOWNLOAD_LATEST)).click()
  val started = device.wait(Until.hasObject(By.textContains(SYNC_RUNNING)), 2_000L)
  if (started && !device.wait(Until.gone(By.textContains(SYNC_RUNNING)), 30_000L)) {
    error("The sync pass did not finish within 30 s.")
  }
  if (!device.hasObject(By.textContains(SYNC_IDLE))) error("The sync pass did not end idle.")
}
