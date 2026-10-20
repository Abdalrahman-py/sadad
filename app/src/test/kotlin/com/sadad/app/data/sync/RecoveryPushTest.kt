package com.sadad.app.data.sync

import com.sadad.app.data.db.Events
import com.sadad.app.data.ledger.OutboxState
import com.sadad.app.data.recovery.Recovery
import com.sadad.ledger.Commands
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerJson
import com.sadad.ledger.OperationsRequest
import com.sadad.ledger.Origin
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecoveryPushTest {
  @Test
  fun aPermitSendsItsRowsInTheirOwnRequestAndOwnRowsStayNormal() = runTest {
    SyncFixture.create().use { f ->
      val own = f.queue(1).single()
      val oldInstallation = UUID.randomUUID().toString()
      val permit = UUID.randomUUID().toString()
      val foreign =
          Commands.contactCreate(
              Origin(oldInstallation, 1, Events.EPOCH),
              Events.at(5),
              name = "قديم",
              entityId = UUID.randomUUID().toString(),
          )
      f.scope.outbox.append(listOf(foreign))
      Recovery.setPermit(f.scope, oldInstallation, permit)

      f.server.enqueue(receiptResponse(listOf(foreign)))
      f.server.enqueue(receiptResponse(listOf(own)))
      f.page()

      assertEquals(SyncStatus.Idle, f.engine.pass(f.scope))

      // The recovered rows go first, so a row that depends on one finds it on the server.
      val recovery = f.server.takeRequest()
      assertEquals(permit, recovery.headers["x-sadad-recovery"])
      assertEquals(listOf(foreign.operationId), sentIds(recovery))
      val normal = f.server.takeRequest()
      assertNull(normal.headers["x-sadad-recovery"])
      assertEquals(listOf(own.operationId), sentIds(normal))
    }
  }

  @Test
  fun aRecoveryRefusalLeavesTheRowsQueuedUnderTheirPermit() = runTest {
    SyncFixture.create().use { f ->
      val oldInstallation = UUID.randomUUID().toString()
      val permit = UUID.randomUUID().toString()
      val foreign =
          Commands.contactCreate(
              Origin(oldInstallation, 1, Events.EPOCH),
              Events.at(5),
              name = "قديم",
              entityId = UUID.randomUUID().toString(),
          )
      f.scope.outbox.append(listOf(foreign))
      Recovery.setPermit(f.scope, oldInstallation, permit)
      f.server.enqueue(refusalResponse("recovery_not_authorized"))
      f.page()

      assertEquals(SyncStatus.Failed("recovery_not_authorized"), f.engine.pass(f.scope))

      val row = f.scope.outbox.snapshot().single()
      assertEquals(OutboxState.QUEUED, row.state)
      assertEquals(permit, row.recoveryPermit)
      assertTrue(f.scope.outbox.queued().any { it.operationId == foreign.operationId })
    }
  }

  @Test
  fun aRefusedPermitStillSendsOwnRowsAndDownloads() = runTest {
    SyncFixture.create().use { f ->
      val own = f.queue(1).single()
      val oldInstallation = UUID.randomUUID().toString()
      val foreign = foreignContact(oldInstallation)
      f.scope.outbox.append(listOf(foreign))
      Recovery.setPermit(f.scope, oldInstallation, UUID.randomUUID().toString())
      f.server.enqueue(refusalResponse("recovery_not_authorized"))
      f.server.enqueue(receiptResponse(listOf(own)))
      f.page()

      assertEquals(SyncStatus.Failed("recovery_not_authorized"), f.engine.pass(f.scope))

      assertEquals(
          "/v1/mobile/operations",
          f.server.takeRequest().target,
      )
      assertEquals(listOf(own.operationId), sentIds(f.server.takeRequest()))
      assertEquals("/v1/mobile/changes?after=0&limit=500", f.server.takeRequest().target)
      val rows = f.scope.outbox.snapshot().associateBy { it.operationId }
      assertEquals(OutboxState.ACCEPTED, rows.getValue(own.operationId).state)
      assertEquals(OutboxState.QUEUED, rows.getValue(foreign.operationId).state)
    }
  }

  @Test
  fun aRowThatDependsOnARecoveredRowWaitsForItsPermitThenFollowsIt() = runTest {
    SyncFixture.create().use { f ->
      val oldInstallation = UUID.randomUUID().toString()
      val foreign = foreignContact(oldInstallation)
      f.scope.outbox.append(listOf(foreign))
      // A new debt-style row of this phone that names the recovered row in dependsOn.
      val dependent =
          Commands.contactCreate(
              Origin(f.scope.account.installationId, 1, Events.EPOCH),
              Events.at(9),
              name = "تابع",
              entityId = UUID.randomUUID().toString(),
              dependsOn = listOf(foreign.operationId),
          )
      f.scope.outbox.append(listOf(dependent))
      f.page()

      assertEquals(SyncStatus.Idle, f.engine.pass(f.scope))

      // Without a permit nothing is sent (the dependent would be refused and block the pass).
      assertEquals("/v1/mobile/changes?after=0&limit=500", f.server.takeRequest().target)
      assertEquals(
          OutboxState.QUEUED,
          f.scope.outbox.snapshot().first { it.operationId == dependent.operationId }.state,
      )

      Recovery.setPermit(f.scope, oldInstallation, UUID.randomUUID().toString())
      f.server.enqueue(receiptResponse(listOf(foreign)))
      f.server.enqueue(receiptResponse(listOf(dependent)))
      f.page()

      assertEquals(SyncStatus.Idle, f.engine.pass(f.scope))

      assertEquals(listOf(foreign.operationId), sentIds(f.server.takeRequest()))
      assertEquals(listOf(dependent.operationId), sentIds(f.server.takeRequest()))
    }
  }

  private fun foreignContact(installation: String): LedgerCommand =
      Commands.contactCreate(
          Origin(installation, 1, Events.EPOCH),
          Events.at(5),
          name = "قديم",
          entityId = UUID.randomUUID().toString(),
      )

  private fun sentIds(request: mockwebserver3.RecordedRequest): List<String> =
      LedgerJson.decodeFromString(
              OperationsRequest.serializer(),
              checkNotNull(request.body).utf8(),
          )
          .operations
          .map(LedgerCommand::operationId)
}
