package com.sadad.app.data.recovery

import com.sadad.app.data.db.Events
import com.sadad.app.data.ledger.OutboxState
import com.sadad.app.data.sync.SyncFixture
import com.sadad.app.data.sync.SyncStatus
import com.sadad.app.data.sync.receiptResponse
import com.sadad.ledger.Cents
import com.sadad.ledger.Commands
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerJson
import com.sadad.ledger.OperationsRequest
import com.sadad.ledger.Origin
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ManifestTest {
  @Test
  fun theManifestIsExactlyTheCommandsSentLaterForThatPermit() = runTest {
    SyncFixture.create().use { f ->
      val oldInstallation = UUID.randomUUID().toString()
      val permit = UUID.randomUUID().toString()
      val origin = Origin(oldInstallation, 1, Events.EPOCH)
      val contact =
          Commands.contactCreate(
              origin,
              Events.at(0),
              name = "سامي",
              entityId = UUID.randomUUID().toString(),
          )
      val debt =
          Commands.debtCreate(
              origin,
              Events.at(1),
              contactId = contact.entityId,
              direction = Direction.RECEIVABLE,
              amount = Cents(5_000),
              entityId = UUID.randomUUID().toString(),
              dependsOn = listOf(contact.operationId),
          )
      f.scope.outbox.append(listOf(contact, debt))

      val manifest = Recovery.manifest(f.scope, oldInstallation)
      val manifestJson = Json.parseToJsonElement(String(manifest, Charsets.UTF_8)).jsonArray
      assertEquals(2, manifestJson.size)

      Recovery.setPermit(f.scope, oldInstallation, permit)
      f.server.enqueue(receiptResponse(listOf(contact, debt)))
      f.page()

      assertEquals(SyncStatus.Idle, f.engine.pass(f.scope))

      val request = f.server.takeRequest()
      assertEquals(permit, request.headers["x-sadad-recovery"])
      val sent =
          LedgerJson.decodeFromString(
                  OperationsRequest.serializer(),
                  checkNotNull(request.body).utf8(),
              )
              .operations
      assertEquals(
          manifestJson,
          JsonArray(sent.map { LedgerJson.encodeToJsonElement(LedgerCommand.serializer(), it) }),
      )
    }
  }

  @Test
  fun onlyOfflineCommandsGetThePermitAndTheManifest() = runTest {
    SyncFixture.create().use { f ->
      val oldInstallation = UUID.randomUUID().toString()
      val permit = UUID.randomUUID().toString()
      val origin = Origin(oldInstallation, 1, Events.EPOCH)
      val contact =
          Commands.contactCreate(
              origin,
              Events.at(0),
              name = "سامي",
              entityId = UUID.randomUUID().toString(),
          )
      val archive =
          Commands.contactArchive(
              origin,
              Events.at(1),
              contactId = contact.entityId,
              expectedVersion = 1,
              reason = "سبب",
          )
      f.scope.outbox.append(listOf(contact, archive))

      val manifest =
          Json.parseToJsonElement(
                  String(Recovery.manifest(f.scope, oldInstallation), Charsets.UTF_8)
              )
              .jsonArray
      assertEquals(
          listOf(LedgerJson.encodeToJsonElement(LedgerCommand.serializer(), contact)),
          manifest,
      )

      Recovery.setPermit(f.scope, oldInstallation, permit)
      f.server.enqueue(receiptResponse(listOf(contact)))
      f.page()

      assertEquals(SyncStatus.Idle, f.engine.pass(f.scope))

      val sent =
          LedgerJson.decodeFromString(
                  OperationsRequest.serializer(),
                  checkNotNull(f.server.takeRequest().body).utf8(),
              )
              .operations
      assertEquals(listOf(contact.operationId), sent.map { it.operationId })
      // An online-only command from another installation cannot be sent by this one and no permit
      // can name it, so the pass names it unsendable (discard is its only exit).
      val waiting = f.scope.outbox.snapshot().single { it.operationId == archive.operationId }
      assertEquals(OutboxState.UNSENDABLE, waiting.state)
      assertNull(waiting.recoveryPermit)
    }
  }
}
