package com.sadad.app.feature.reports

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.export.StatementFormat
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.history.Period
import com.sadad.app.feature.history.PeriodPreset
import com.sadad.app.feature.history.bounds
import com.sadad.app.feature.history.statementSource
import com.sadad.app.feature.lock.AppLock
import com.sadad.app.testing.TestGraph
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(application = com.sadad.app.ui.UiTestApplication::class)
class ReportsExportTest {
  @get:Rule val main = MainDispatcherRule()

  @Test
  fun sharingWhenThePdfCannotBeWrittenShowsTheMessageAndFreesTheScreen() = runTest {
    TestGraph.signedIn().use { fixture ->
      fixture.seedOctober()
      val model = ReportsViewModel(fixture.graph.statementSource(), SavedStateHandle(), clock)
      fixture.closeWithStore(model.viewModelScope)
      val sami =
          model.state
              .first { it.loaded && it.people.isNotEmpty() }
              .people
              .first { it.name == "سامي" }

      // A file where the exports directory belongs fails the write before any PdfDocument exists.
      // Robolectric's PdfDocument can never close (its native handle is 0), so building one here
      // leaks a CloseGuard that StrictMode reports during some later test.
      File(fixture.context.cacheDir, "exports").writeText("not a directory")
      model.shareStatement(fixture.context, sami.contactId)

      val state = model.state.first { it.error != null && !it.busy }
      assertEquals(
          fixture.context.getString(com.sadad.app.R.string.reports_open_failed),
          state.error,
      )
      assertFalse(state.saved)
    }
  }

  @Test
  fun aContactThatVanishedIsRefusedWithoutLeavingTheScreenBusy() = runTest {
    TestGraph.signedIn().use { fixture ->
      fixture.seedOctober()
      val model = ReportsViewModel(fixture.graph.statementSource(), SavedStateHandle(), clock)
      fixture.closeWithStore(model.viewModelScope)
      model.state.first { it.loaded && it.people.isNotEmpty() }

      model.openStatement(fixture.context, "no-such-contact")
      val opened = model.state.first { it.error != null }
      model.shareStatement(fixture.context, "no-such-contact")

      val nothing = fixture.context.getString(com.sadad.app.R.string.reports_nothing_to_export)
      assertEquals(nothing, opened.error)
      assertFalse(opened.busy)
      assertEquals(nothing, model.state.value.error)
      assertFalse(model.state.value.busy)
    }
  }

  @Test
  fun theNothingToExportMessageClearsWhenTheFirstContactArrives() = runTest {
    TestGraph.signedIn().use { fixture ->
      val model = ReportsViewModel(fixture.graph.statementSource(), SavedStateHandle(), clock)
      fixture.closeWithStore(model.viewModelScope)
      model.state.first { it.loaded }
      assertFalse(model.canExport(fixture.context))
      model.state.first { it.error != null }

      fixture.confirmedContact("سامي", at("2026-10-01T09:00:00Z"))

      assertEquals(null, model.state.first { it.registered == 1 }.error)
    }
  }

  @Test
  fun aPhoneWithoutAPdfViewerShowsTheMessageAndKeepsTheLockArmed() = runTest {
    TestGraph.signedIn().use { fixture ->
      fixture.seedOctober()
      val model =
          ReportsViewModel(fixture.graph.statementSource(), SavedStateHandle(), clock) {
              _,
              _,
              _,
              _,
              _,
              _ ->
            Uri.parse("content://com.sadad.app.files/exports/statement.pdf")
          }
      fixture.closeWithStore(model.viewModelScope)
      val sami =
          model.state
              .first { it.loaded && it.people.isNotEmpty() }
              .people
              .first { it.name == "سامي" }
      drainLockSkip(fixture.context)

      model.openStatement(NoPdfViewer(fixture.context), sami.contactId)

      val state = model.state.first { it.error != null && !it.busy }
      assertEquals(
          fixture.context.getString(com.sadad.app.R.string.reports_open_failed),
          state.error,
      )
      // No skip was armed, so the next long absence still locks.
      assertTrue(locksAfterAbsence(fixture.context))
    }
  }

  @Test
  fun anEmptyStoreHasNothingToExport() = runTest {
    TestGraph.signedIn().use { fixture ->
      val model = ReportsViewModel(fixture.graph.statementSource(), SavedStateHandle(), clock)
      fixture.closeWithStore(model.viewModelScope)
      model.state.first { it.loaded }

      assertFalse(model.canExport(fixture.context))

      val state = model.state.first { it.error != null }
      assertEquals(
          fixture.context.getString(com.sadad.app.R.string.reports_nothing_to_export),
          state.error,
      )
      assertEquals(0, state.registered)
    }
  }

  @Test
  fun exportingBeforeTheLedgerHasLoadedShowsTheFailureMessage() = runTest {
    TestGraph.unsigned().use { fixture ->
      val model = ReportsViewModel(fixture.graph.statementSource(), SavedStateHandle(), clock)
      fixture.closeWithStore(model.viewModelScope)

      assertFalse(model.canExport(fixture.context))

      val state = model.state.first { it.error != null }
      assertEquals(
          fixture.context.getString(com.sadad.app.R.string.reports_export_failed),
          state.error,
      )
      assertFalse(state.loaded)
    }
  }

  @Test
  fun aPhoneWithoutADocumentPickerShowsTheFailureMessage() = runTest {
    TestGraph.signedIn().use { fixture ->
      fixture.seedOctober()
      val model = ReportsViewModel(fixture.graph.statementSource(), SavedStateHandle(), clock)
      fixture.closeWithStore(model.viewModelScope)
      model.state.first { it.loaded }

      model.pickerUnavailable(fixture.context)

      val state = model.state.first { it.error != null }
      assertEquals(
          fixture.context.getString(com.sadad.app.R.string.reports_export_failed),
          state.error,
      )
      assertFalse(state.busy)
    }
  }

  @Test
  fun changingThePeriodClearsTheLastExportMessage() = runTest {
    TestGraph.signedIn().use { fixture ->
      val model = ReportsViewModel(fixture.graph.statementSource(), SavedStateHandle(), clock)
      fixture.closeWithStore(model.viewModelScope)
      model.state.first { it.loaded }
      model.canExport(fixture.context)
      model.state.first { it.error != null }

      model.selectPreset(PeriodPreset.MONTH)

      assertEquals(null, model.state.first { it.period == Period.Month(OCTOBER) }.error)
    }
  }

  @Test
  fun savingWritesTheChosenFormatToTheDocumentAndReportsIt() = runTest {
    TestGraph.signedIn().use { fixture ->
      fixture.seedOctober()
      val model = ReportsViewModel(fixture.graph.statementSource(), SavedStateHandle(), clock)
      fixture.closeWithStore(model.viewModelScope)
      model.state.first { it.loaded }
      val file = File.createTempFile("report", ".xlsx", fixture.context.cacheDir)
      try {
        model.saveStatement(fixture.context, Uri.fromFile(file), StatementFormat.XLSX)

        val state = model.state.first { it.saved }
        assertTrue(file.length() > 0)
        assertEquals(null, state.error)
        // XLSX is a zip archive.
        assertEquals("PK", file.readBytes().decodeToString(0, 2))
      } finally {
        file.delete()
      }
    }
  }

  @Test
  fun aDocumentThatCannotBeOpenedShowsTheFailureMessage() = runTest {
    TestGraph.signedIn().use { fixture ->
      fixture.seedOctober()
      val model = ReportsViewModel(fixture.graph.statementSource(), SavedStateHandle(), clock)
      fixture.closeWithStore(model.viewModelScope)
      model.state.first { it.loaded }

      model.saveStatement(
          fixture.context,
          Uri.parse("content://com.sadad.app.missing/document"),
          StatementFormat.PDF,
      )

      val state = model.state.first { it.error != null }
      assertEquals(
          fixture.context.getString(com.sadad.app.R.string.reports_export_failed),
          state.error,
      )
      assertFalse(state.saved)
    }
  }

  @Test
  fun openingAContactStatementLaunchesAPdfViewerWithReadAccess() = runTest {
    TestGraph.signedIn().use { fixture ->
      fixture.seedOctober()
      val written = mutableListOf<Triple<String?, Instant?, Instant?>>()
      val model =
          ReportsViewModel(fixture.graph.statementSource(), SavedStateHandle(), clock) {
              _,
              _,
              _,
              contactId,
              from,
              to ->
            written += Triple(contactId, from, to)
            Uri.parse("content://com.sadad.app.files/exports/statement.pdf")
          }
      fixture.closeWithStore(model.viewModelScope)
      val sami =
          model.state
              .first { it.loaded && it.people.isNotEmpty() }
              .people
              .first { it.name == "سامي" }
      model.selectPreset(PeriodPreset.MONTH)
      drainLockSkip(fixture.context)

      model.openStatement(fixture.context, sami.contactId)

      val launched = startedActivity { model.state.value.error }
      model.state.first { !it.busy }
      assertEquals(Intent.ACTION_VIEW, launched.action)
      assertEquals("application/pdf", launched.type)
      assertTrue(launched.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
      // The statement covers the chosen month as a half-open range: the next month's first
      // instant is not in it.
      val month = Period.Month(OCTOBER).bounds(zone)
      assertEquals(listOf(Triple(sami.contactId, month.from, month.to)), written)
      // Coming back from the viewer after a long absence must not lock the app.
      assertFalse(locksAfterAbsence(fixture.context))
    }
  }

  /** `startActivity` as a phone without any PDF app answers it. */
  private class NoPdfViewer(base: Context) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this

    override fun startActivity(intent: Intent) = throw ActivityNotFoundException()
  }

  /** Consumes a skip another test left armed on the process-wide lock. */
  private fun drainLockSkip(context: Context) = AppLock.get(context).appStarted()

  /** Whether leaving the app and returning after two seconds locks it; a skip is consumed. */
  private fun locksAfterAbsence(context: Context): Boolean {
    val lock = AppLock.get(context)
    lock.markUnlocked()
    lock.appStopped()
    ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
    lock.appStarted()
    return lock.locked.value
  }

  /** The fake writer answers at once and the test dispatcher is unconfined: no waiting needed. */
  private fun startedActivity(failure: () -> String?): Intent {
    val application = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
    return application.nextStartedActivity
        ?: error("no activity was started; state error: ${failure()}")
  }
}
