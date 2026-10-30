package com.sadad.app.data.session

import com.sadad.app.data.db.Events
import com.sadad.app.data.ledger.OutboxState
import com.sadad.app.feature.notifications.DueReminders
import com.sadad.app.feature.notifications.Reminder
import com.sadad.app.feature.trash.TrashHidden
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.STORE_B
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.Home
import com.sadad.app.ui.Login
import com.sadad.ledger.Cents
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StoresTest {

  @Test
  fun switchingAtoBtoAkeepsEachStoreToItself() = runTest {
    TestGraph.signedIn(STORE_A).use { fixture ->
      fixture.seed { addContact("سامي", null, null, null, null) }
      val aRow = fixture.graph.current.value!!.outbox.snapshot().single()

      fixture.signIn(STORE_B)
      fixture.activate(STORE_B)
      val b = fixture.graph.current.value!!
      val bView = b.repository.view.filterNotNull().first()
      assertTrue(bView.contacts.isEmpty())
      assertTrue(b.outbox.snapshot().isEmpty())

      fixture.graph.sessions.switchTo(STORE_A)

      val a = fixture.graph.current.first { it?.account?.storeUserId == STORE_A }!!
      val view =
          a.repository.view.filterNotNull().first { it.contacts.any { c -> c.name == "سامي" } }
      assertTrue(view.contacts.single().pending)
      val rowNow = a.outbox.snapshot().single()
      assertEquals(aRow.operationId, rowNow.operationId)
      assertEquals(OutboxState.QUEUED, rowNow.state)
    }
  }

  @Test
  fun twoStoresUseDifferentDatabaseFilesAndInstallations() = runTest {
    TestGraph.unsigned().use { fixture ->
      fixture.signIn(STORE_A)
      val a = fixture.activate(STORE_A)
      fixture.signIn(STORE_B)
      val b = fixture.activate(STORE_B)

      assertNotEquals(a.account.installationId, b.account.installationId)
      val aFile = fixture.databaseFile(STORE_A)
      val bFile = fixture.databaseFile(STORE_B)
      assertTrue(aFile.exists())
      assertTrue(bFile.exists())
      assertNotEquals(aFile.absolutePath, bFile.absolutePath)
    }
  }

  @Test
  fun aStoredSessionStartsHomeWithItsStoreOpen() = runTest {
    TestGraph.signedIn(STORE_A).use { fixture ->
      fixture.graph.closeStore()

      assertEquals(Home, fixture.graph.startRoute())

      assertNotNull(fixture.graph.current.value)
    }
  }

  @Test
  fun anInstallationSavedAfterOpeningReachesTheOpenStoresOrigin() = runTest {
    TestGraph.unsigned().use { fixture ->
      val scope = fixture.signIn(STORE_A)
      scope.setEpoch(Events.EPOCH)
      assertNull(scope.origin())

      fixture.graph.accounts.update(STORE_A) {
        it.copy(installation = StoredInstallation("active", generation = 2))
      }

      val origin =
          withContext(Dispatchers.Default) {
            withTimeout(5_000) {
              var origin = scope.origin()
              while (origin == null) {
                delay(10)
                origin = scope.origin()
              }
              origin
            }
          }
      assertEquals(2, origin.generation)
    }
  }

  @Test
  fun switchingToAStoreWithoutATokenGoesToLogin() = runTest {
    TestGraph.signedIn(STORE_A).use { fixture ->
      fixture.graph.accounts.clearRefreshToken(STORE_A)
      fixture.signIn(STORE_B)
      fixture.activate(STORE_B)

      fixture.graph.sessions.switchTo(STORE_A)

      assertEquals(Login, fixture.graph.startRoute())
    }
  }

  @Test
  fun removingAStoreRemovesItsPhotoItsHiddenTrashAndItsReminders() = runTest {
    TestGraph.signedIn(STORE_A).use { fixture ->
      val context = fixture.context
      val photos = File(context.filesDir, "photos").apply { mkdirs() }
      val storePhoto = File(photos, "$STORE_A-store.png").apply { writeBytes(byteArrayOf(1)) }
      val accountPhoto = File(photos, "$STORE_A-account.png").apply { writeBytes(byteArrayOf(1)) }
      TrashHidden.get(context, STORE_A).hide("contact-1")
      DueReminders.get(context)
          .record(
              Reminder(
                  STORE_A,
                  "debt-1",
                  "contact-1",
                  "سامي",
                  "متجري",
                  Cents(1000),
                  "2026-11-01",
                  "today",
                  0L,
              )
          )
      assertEquals(1, DueReminders.get(context).inbox(STORE_A).first().size)

      assertTrue(fixture.graph.removeStore(STORE_A))

      assertFalse("the store photo must go with the store", storePhoto.exists())
      assertFalse(accountPhoto.exists())
      assertFalse(File(context.filesDir, "trash-$STORE_A.preferences_pb").exists())
      assertTrue(TrashHidden.get(context, STORE_A).ids.first().isEmpty())
      assertTrue(
          "the store's reminders must go with the store",
          DueReminders.get(context).inbox(STORE_A).first().isEmpty(),
      )
    }
  }

  @Test
  fun removingAStoreWithQueuedRowsNeedsTwoConfirmations() = runTest {
    TestGraph.signedIn(STORE_A).use { fixture ->
      fixture.seed { addContact("سامي", null, null, null, null) }
      val file = fixture.databaseFile(STORE_A)

      assertFalse(fixture.graph.removeStore(STORE_A))
      assertNotNull(fixture.graph.accounts.account(STORE_A))
      assertTrue(file.exists())

      assertTrue(fixture.graph.removeStore(STORE_A, confirmedTwice = true))

      assertNull(fixture.graph.accounts.account(STORE_A))
      assertFalse(file.exists())
    }
  }
}
