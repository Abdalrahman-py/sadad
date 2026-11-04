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

// The texts below are copied from app/src/main/res/values (the app does not set
// testTagsAsResourceId, so Compose test tags are not visible to UiAutomator). If a string changes
// the benchmark fails with a clear message; update it here.
private const val NAV_PEOPLE = "الأشخاص"
private const val NAV_STORE = "المتجر"
private const val NAV_RECORD = "تسجيل دين أو دفعة"
private const val HOME_TOTAL = "لك عند الناس"
private const val PEOPLE_SEARCH = "ابحث بالاسم أو رقم الهاتف"
private const val CONTACT_MENU = "خيارات أخرى"
private const val PICKER_TITLE = "تسجيل لمن؟"
private const val SAVE_TEN = "سجّل ₪10"
private const val RECORD_ANOTHER = "تسجيل آخر"
private const val UNDO_COUNTDOWN = "تراجع ·"
private const val SYNC_NOW = "مزامنة الآن"
private const val SYNC_DONE = "تمت المزامنة بنجاح."

/** Quick add commits after its 8 s undo window; the `save_debt` span runs at the end of it. */
private const val UNDO_WINDOW_MS = 12_000L

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
 * Starts the app (cold or warm, as the caller set up) and fails unless it reached a signed-in store
 * with its totals on Home, so the `ledger_load` and `ledger_project` spans have closed.
 */
fun MacrobenchmarkScope.startToHome() {
  startActivityAndWait()
  if (!device.wait(Until.hasObject(By.desc(NAV_PEOPLE)), WAIT_MS)) precondition()
  if (!device.wait(Until.hasObject(By.text(HOME_TOTAL)), WAIT_MS)) error("No totals on Home.")
}

fun MacrobenchmarkScope.openContacts() {
  device.findObject(By.desc(NAV_PEOPLE)).click()
  if (!device.wait(Until.hasObject(By.desc(PEOPLE_SEARCH)), WAIT_MS)) {
    error("The People screen did not appear.")
  }
  if (!device.wait(Until.hasObject(By.scrollable(true)), WAIT_MS)) {
    error("The People list is empty. Seed the store first (ticket 62).")
  }
}

/** Flings down and up alternately, so a short list still moves on every fling. */
fun MacrobenchmarkScope.flingContacts(times: Int) {
  val list = device.findObject(By.scrollable(true)) ?: error("No scrollable People list.")
  list.setGestureMargin(device.displayWidth / 5)
  repeat(times) { index ->
    list.fling(if (index % 2 == 0) Direction.DOWN else Direction.UP)
    device.waitForIdle()
  }
}

/** Sets the search field one letter longer at a time, as typing does. */
fun MacrobenchmarkScope.typeInPeopleSearch(query: String) {
  val field = device.findObject(By.desc(PEOPLE_SEARCH)) ?: error("No People search field.")
  field.click()
  for (length in 1..query.length) {
    field.text = query.take(length)
    device.waitForIdle()
  }
}

fun MacrobenchmarkScope.openFirstContact() {
  val list = device.findObject(By.scrollable(true)) ?: error("No scrollable People list.")
  val row = list.findObject(By.clickable(true)) ?: error("The People list has no row.")
  row.click()
  if (!device.wait(Until.hasObject(By.desc(CONTACT_MENU)), WAIT_MS)) {
    error("The person's page did not open.")
  }
}

/** The record button in the bottom bar; ends when the picker's list of people shows. */
fun MacrobenchmarkScope.openQuickAdd() {
  device.findObject(By.desc(NAV_RECORD)).click()
  if (!device.wait(Until.hasObject(By.text(PICKER_TITLE)), WAIT_MS)) {
    error("Quick add did not open.")
  }
  if (!device.wait(Until.hasObject(By.scrollable(true)), WAIT_MS)) {
    error("Quick add's picker has no people.")
  }
}

/** From the picker: the first person, then 10 on the keypad, ending on the amount step. */
fun MacrobenchmarkScope.typeTenForFirstPerson() {
  val list = device.findObject(By.scrollable(true)) ?: error("No scrollable picker list.")
  val row = list.findObject(By.clickable(true)) ?: error("The picker has no row.")
  row.click()
  if (!device.wait(Until.hasObject(By.desc("1")), WAIT_MS)) {
    error("The amount step did not open.")
  }
  device.findObject(By.desc("1")).click()
  device.findObject(By.desc("0")).click()
  if (!device.wait(Until.hasObject(By.textStartsWith(SAVE_TEN)), WAIT_MS)) {
    error("The save button did not show the amount.")
  }
}

/** Saves and waits out the undo window; the app's `save_debt` span is what gets measured. */
fun MacrobenchmarkScope.saveTenShekelDebt() {
  typeTenForFirstPerson()
  device.findObject(By.textStartsWith(SAVE_TEN)).click()
  if (!device.wait(Until.hasObject(By.text(RECORD_ANOTHER)), WAIT_MS)) {
    error("Quick add's done step did not open.")
  }
  if (!device.wait(Until.gone(By.textStartsWith(UNDO_COUNTDOWN)), UNDO_WINDOW_MS)) {
    error("The undo window did not end.")
  }
  device.waitForIdle()
}

/** The Store tab, scrolled to «مزامنة الآن». Its label changes with the sync dot. */
fun MacrobenchmarkScope.openSettings() {
  device.findObject(By.descStartsWith(NAV_STORE)).click()
  if (!device.wait(Until.hasObject(By.scrollable(true)), WAIT_MS)) error("No Store screen.")
  val list = device.findObject(By.scrollable(true))
  repeat(5) { if (!device.hasObject(By.text(SYNC_NOW))) list.scroll(Direction.DOWN, 0.8f) }
  if (!device.hasObject(By.text(SYNC_NOW))) error("No sync button on the Store screen.")
}

/**
 * One idle pass from the Store screen. An idle pass reports done within about 6 s, after the
 * `sync_pass` span has closed, so waiting for that message keeps the iteration open long enough.
 */
fun MacrobenchmarkScope.syncOnce() {
  device.findObject(By.text(SYNC_NOW)).click()
  if (!device.wait(Until.hasObject(By.text(SYNC_DONE)), 30_000L)) {
    error("The sync pass did not end idle within 30 s.")
  }
}
