package com.sadad.app.data.recovery

import com.sadad.app.data.crypto.SoftwareSigner
import com.sadad.app.data.db.Events
import com.sadad.app.data.ledger.OutboxState
import com.sadad.app.data.net.LeaseVerifier
import com.sadad.app.data.session.StoredInstallation
import com.sadad.app.data.sync.LeaseStore
import com.sadad.app.data.sync.SyncEngine
import com.sadad.app.data.sync.SyncStatus
import com.sadad.app.data.sync.pageResponse
import com.sadad.app.data.sync.receiptResponse
import com.sadad.app.data.sync.signedLease
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.TestGraph
import com.sadad.ledger.LedgerJson
import com.sadad.ledger.OperationsRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RegisterAgainTest {
  @Test
  fun aRevokedPhoneRegistersANewIdentityThatEnrollsWhileOldRowsWait() = runTest {
    TestGraph.signedIn(STORE_A).use { fixture ->
      fixture.server.takeRequest()
      fixture.seed { addContact("سامي", null, null, null, null) }
      val old = checkNotNull(fixture.graph.accounts.account(STORE_A))
      fixture.graph.accounts.update(STORE_A) {
        it.copy(installation = StoredInstallation("revoked", 1))
      }

      fixture.graph.sessions.registerAgain(checkNotNull(fixture.graph.current.value))

      val fresh = checkNotNull(fixture.graph.accounts.account(STORE_A))
      assertNotEquals(old.installationId, fresh.installationId)
      assertNotEquals(old.keyAlias, fresh.keyAlias)
      assertTrue(fresh.keyAlias.startsWith("installation-$STORE_A-"))
      assertNull(fresh.installation)
      val scope = checkNotNull(fixture.graph.current.value)
      assertEquals(fresh.installationId, scope.signer.installationId)
      val oldRows = scope.outbox.snapshot()
      assertEquals(1, oldRows.size)
      assertEquals(OutboxState.QUEUED, oldRows.single().state)
      assertEquals(old.installationId, oldRows.single().command.originInstallationId)

      val leaseSigner = SoftwareSigner("lease-server")
      val now = System.currentTimeMillis()
      val leaseJob = SupervisorJob()
      val lease =
          LeaseStore(
              File(fixture.context.cacheDir, "lease-${UUID.randomUUID()}.preferences_pb"),
              CoroutineScope(leaseJob + Dispatchers.IO),
              LeaseVerifier(leaseSigner.publicKey.x, leaseSigner.publicKey.y),
              { 5 },
              { now },
          )
      try {
        val engine =
            SyncEngine(fixture.graph.accounts, fixture.graph.sessions, fixture.graph.ledgerApi) {
              lease
            }
        fixture.server.enqueue(
            MockResponse.Builder()
                .body(
                    """{"installation":{"installationId":"${fresh.installationId}","generation":2,"status":"active","label":"Test Device","staffName":"","permissions":{}}}"""
                )
                .build()
        )
        val active = fresh.copy(installation = StoredInstallation("active", 2))
        fixture.server.enqueue(leaseResponse(leaseSigner, active, now))
        fixture.server.enqueue(pageResponse(Events.page(emptyList())))

        assertEquals(SyncStatus.Idle, engine.pass(scope))

        assertEquals("/v1/mobile/enroll", fixture.server.takeRequest().target)
        assertEquals("/v1/mobile/lease", fixture.server.takeRequest().target)
        assertEquals("/v1/mobile/changes?after=0&limit=500", fixture.server.takeRequest().target)
        assertNull(fixture.server.takeRequest(0, TimeUnit.SECONDS))

        fixture.seed { addContact("ليلى", null, null, null, null) }
        val own =
            scope.outbox.snapshot().single {
              it.command.originInstallationId == fresh.installationId
            }
        fixture.server.enqueue(receiptResponse(listOf(own.command)))
        fixture.server.enqueue(pageResponse(Events.page(emptyList())))

        assertEquals(SyncStatus.Idle, engine.pass(scope))

        val operations = fixture.server.takeRequest()
        assertEquals("/v1/mobile/operations", operations.target)
        assertNull(operations.headers["x-sadad-recovery"])
        val sent =
            LedgerJson.decodeFromString(
                OperationsRequest.serializer(),
                checkNotNull(operations.body).utf8(),
            )
        assertEquals(listOf(own.operationId), sent.operations.map { it.operationId })
        assertEquals("/v1/mobile/changes?after=0&limit=500", fixture.server.takeRequest().target)
        assertTrue(
            scope.outbox
                .snapshot()
                .filter {
                  it.command.originInstallationId == old.installationId
                }
                .all { it.state == OutboxState.QUEUED }
        )
      } finally {
        leaseJob.cancelAndJoin()
      }
    }
  }

  @Test
  fun anActivePhoneKeepsItsIdentity() = runTest {
    TestGraph.signedIn(STORE_A).use { fixture ->
      fixture.server.takeRequest()
      val before = checkNotNull(fixture.graph.accounts.account(STORE_A))

      fixture.graph.sessions.registerAgain(checkNotNull(fixture.graph.current.value))

      val after = checkNotNull(fixture.graph.accounts.account(STORE_A))
      assertEquals(before.installationId, after.installationId)
      assertEquals(before.keyAlias, after.keyAlias)
      assertEquals("active", after.installation?.status)
    }
  }

  private fun leaseResponse(
      signer: SoftwareSigner,
      account: com.sadad.app.data.session.Account,
      now: Long,
  ): MockResponse {
    val lease = signedLease(signer, account, now)
    return MockResponse.Builder()
        .body("""{"lease":{"payload":"${lease.raw}","signature":"${lease.signature}"}}""")
        .build()
  }
}
