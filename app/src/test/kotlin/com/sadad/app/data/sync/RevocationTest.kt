package com.sadad.app.data.sync

import com.sadad.app.testing.STORE_A
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RevocationTest {
  @Test
  fun aPhoneTheServerSaysIsRevokedStoresThatSoRegisteringAgainIsPossible() = runTest {
    SyncFixture.create().use { f ->
      f.queue(1)
      f.server.enqueue(refusalResponse("installation_or_session_revoked", 401))
      // Enrolling asks the server which it was: the installation itself.
      f.server.enqueue(refusalResponse("installation_revoked"))

      assertEquals(SyncStatus.Revoked, f.engine.pass(f.scope))

      val stored = checkNotNull(f.test.graph.accounts.account(STORE_A))
      assertEquals("revoked", stored.installation?.status)
      assertTrue(f.test.graph.sessions.registerAgain(f.scope))
      assertEquals(null, checkNotNull(f.test.graph.accounts.account(STORE_A)).installation)
    }
  }

  @Test
  fun aRevokedSessionAloneDoesNotMakeThePhoneRegisterAgain() = runTest {
    SyncFixture.create().use { f ->
      f.queue(1)
      f.server.enqueue(refusalResponse("installation_or_session_revoked", 401))
      f.server.enqueue(
          MockResponse.Builder()
              .body(
                  """{"installation":{"installationId":"${f.scope.account.installationId}","generation":1,"status":"active","label":"Test Device","staffName":"","permissions":{}}}"""
              )
              .build()
      )

      assertEquals(SyncStatus.Revoked, f.engine.pass(f.scope))

      val stored = checkNotNull(f.test.graph.accounts.account(STORE_A))
      assertEquals("active", stored.installation?.status)
      assertFalse(f.test.graph.sessions.registerAgain(f.scope))
    }
  }

  @Test
  fun anEnrollThatTheServerRefusesAsRevokedIsStoredToo() = runTest {
    SyncFixture.create().use { f ->
      f.test.graph.accounts.update(STORE_A) { it.copy(installation = null) }
      f.server.enqueue(refusalResponse("installation_revoked"))

      assertEquals(SyncStatus.Revoked, f.engine.pass(f.scope))

      assertEquals(
          "revoked",
          checkNotNull(f.test.graph.accounts.account(STORE_A)).installation?.status,
      )
    }
  }
}
