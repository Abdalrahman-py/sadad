package com.sadad.app.feature.settings

import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.STORE_B
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class SettingsAccountTest {
  @get:Rule val main = MainDispatcherRule()

  @Test
  fun signingOutWithUnsentEntriesWarnsFirstAndKeepsTheDatabase() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        fixture.seed { addContact("سامي", null, null, null, null).getOrThrow() }
        val model = kit.viewModel()
        model.state.first { it.loaded }

        model.account.requestSignOut()

        val warned = model.state.first { it.pending is Pending.SignOut }
        assertEquals(Pending.SignOut(STORE_A, unsent = 1), warned.pending)
        assertNotNull(fixture.graph.current.value)

        fixture.server.enqueue(MockResponse.Builder().body("{}").build())
        model.account.confirmSignOut()

        model.state.first { it.outcome == SettingsOutcome.NEEDS_LOGIN }
        assertNull(fixture.graph.current.value)
        assertTrue(fixture.databaseFile(STORE_A).exists())
        val account = checkNotNull(fixture.graph.accounts.account(STORE_A))
        assertNull(account.sealedRefreshToken)
      }
    }
  }

  @Test
  fun cancellingTheSignOutWarningLeavesTheStoreOpen() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        val model = kit.viewModel()
        model.state.first { it.loaded }
        model.account.requestSignOut()
        model.state.first { it.pending is Pending.SignOut }

        model.ui.dismiss()

        assertNull(model.state.first { it.pending == null }.pending)
        assertNotNull(fixture.graph.current.value)
      }
    }
  }

  @Test
  fun removingAStoreWithUnsentEntriesNeedsTwoConfirmations() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        fixture.seed { addContact("سامي", null, null, null, null).getOrThrow() }
        val model = kit.viewModel()
        model.state.first { it.loaded }

        model.account.requestRemove()
        assertEquals(
            Pending.Remove(STORE_A, unsent = 1, confirmed = 0, needed = 2),
            model.state.first { it.pending is Pending.Remove }.pending,
        )
        model.account.confirmRemove()
        assertEquals(
            Pending.Remove(STORE_A, unsent = 1, confirmed = 1, needed = 2),
            model.state.first { (it.pending as? Pending.Remove)?.confirmed == 1 }.pending,
        )
        assertNotNull(fixture.graph.accounts.account(STORE_A))

        model.account.confirmRemove()

        model.state.first { it.outcome == SettingsOutcome.NEEDS_LOGIN }
        assertNull(fixture.graph.accounts.account(STORE_A))
        assertFalse(fixture.databaseFile(STORE_A).exists())
      }
    }
  }

  @Test
  fun removingAStoreWithNothingUnsentNeedsOneConfirmation() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        val model = kit.viewModel()
        model.state.first { it.loaded }

        model.account.requestRemove()
        assertEquals(
            Pending.Remove(STORE_A, unsent = 0, confirmed = 0, needed = 1),
            model.state.first { it.pending is Pending.Remove }.pending,
        )
        model.account.confirmRemove()

        model.state.first { it.outcome == SettingsOutcome.NEEDS_LOGIN }
        assertNull(fixture.graph.accounts.account(STORE_A))
      }
    }
  }

  @Test
  fun switchingToAnotherSavedStoreChangesTheOpenStore() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        fixture.signIn(STORE_B)
        fixture.activate(STORE_B)
        val model = kit.viewModel()
        val listed = model.state.first { it.loaded && it.store.accounts.size == 2 }
        assertEquals(STORE_B, checkNotNull(fixture.graph.current.value).account.storeUserId)
        assertEquals(
            listOf(STORE_B),
            listed.store.accounts.filter { it.active }.map { it.storeUserId },
        )

        model.store.switchTo(STORE_A)

        model.state.first {
          it.store.accounts.firstOrNull { saved -> saved.active }?.storeUserId == STORE_A
        }
        assertEquals(STORE_A, checkNotNull(fixture.graph.current.value).account.storeUserId)
      }
    }
  }

  @Test
  fun signingOutWithNothingUnsentStillAsksFirst() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        val model = kit.viewModel()
        model.state.first { it.loaded }

        model.account.requestSignOut()

        assertEquals(
            Pending.SignOut(STORE_A, unsent = 0),
            model.state.first { it.pending is Pending.SignOut }.pending,
        )
        assertNotNull(fixture.graph.current.value)
      }
    }
  }

  @Test
  fun aWarningForOneStoreIsRefusedWhenAnotherStoreIsOpenByTheTimeItIsConfirmed() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        fixture.seed { addContact("سامي", null, null, null, null).getOrThrow() }
        val model = kit.viewModel()
        model.state.first { it.loaded }
        model.account.requestRemove()
        model.state.first { it.pending is Pending.Remove }
        fixture.signIn(STORE_B)

        model.account.confirmRemove()

        assertNull(model.state.first { it.pending == null }.pending)
        assertNotNull(fixture.graph.accounts.account(STORE_A))
        assertNotNull(fixture.graph.accounts.account(STORE_B))
      }
    }
  }

  @Test
  fun removingAskedWithNothingUnsentAsksAgainWhenEntriesAppearBeforeTheConfirmation() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        val model = kit.viewModel()
        model.state.first { it.loaded }
        model.account.requestRemove()
        model.state.first { it.pending == Pending.Remove(STORE_A, 0, 0, 1) }
        fixture.seed { addContact("سامي", null, null, null, null).getOrThrow() }

        model.account.confirmRemove()

        val asked = model.state.first { (it.pending as? Pending.Remove)?.unsent == 1 }
        assertEquals(Pending.Remove(STORE_A, unsent = 1, confirmed = 0, needed = 2), asked.pending)
        assertNotNull(fixture.graph.accounts.account(STORE_A))
        assertNull(asked.outcome)
        assertFalse(asked.busy)
      }
    }
  }

  @Test
  fun switchingToAStoreWithoutASavedSessionSendsTheUserToLogin() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        fixture.signIn(STORE_B)
        fixture.activate(STORE_B)
        fixture.graph.accounts.clearRefreshToken(STORE_A)
        val model = kit.viewModel()
        model.state.first { it.loaded && it.store.accounts.size == 2 }

        model.store.switchTo(STORE_A)

        model.state.first { it.outcome == SettingsOutcome.NEEDS_LOGIN }
        assertNull(fixture.graph.current.value)
      }
    }
  }
}
