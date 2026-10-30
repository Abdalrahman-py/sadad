package com.sadad.app.data.recovery

import com.sadad.app.data.db.Events
import com.sadad.app.testing.TestGraph
import com.sadad.ledger.Commands
import com.sadad.ledger.Origin
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PermitTest {
  @Test
  fun aPermitIsStoredInItsCanonicalLowercaseForm() = runTest {
    TestGraph.signedIn().use { fixture ->
      val scope = checkNotNull(fixture.graph.current.value)
      val old = UUID.randomUUID().toString()
      val foreign =
          Commands.contactCreate(Origin(old, 1, Events.EPOCH), Events.at(5), name = "قديم")
      scope.outbox.append(listOf(foreign))
      val permit = UUID.randomUUID().toString()
      val manifest = Recovery.manifest(scope, old)

      Recovery.setPermit(scope, old, "  ${permit.uppercase()} ", manifest)

      assertEquals(permit, scope.outbox.snapshot().single().recoveryPermit)
    }
  }

  @Test
  fun aPermitStampsOnlyTheRowsTheManifestNamed() = runTest {
    TestGraph.signedIn().use { fixture ->
      val scope = checkNotNull(fixture.graph.current.value)
      val old = UUID.randomUUID().toString()
      val origin = Origin(old, 1, Events.EPOCH)
      val first =
          Commands.contactCreate(
              origin,
              Events.at(5),
              name = "قديم",
              entityId = UUID.randomUUID().toString(),
          )
      val second =
          Commands.contactCreate(
              origin,
              Events.at(6),
              name = "لاحق",
              entityId = UUID.randomUUID().toString(),
          )
      scope.outbox.append(listOf(first))
      // The admin approves exactly these bytes; the second row is committed after the export.
      val manifest = Recovery.manifest(scope, old)
      scope.outbox.append(listOf(second))
      val permit = UUID.randomUUID().toString()

      val result = Recovery.setPermit(scope, old, permit, manifest)

      val rows = scope.outbox.snapshot().associateBy { it.operationId }
      assertEquals(permit, rows.getValue(first.operationId).recoveryPermit)
      assertNull(
          "a row outside the manifest must stay queued for a later approval",
          rows.getValue(second.operationId).recoveryPermit,
      )
      assertEquals(1, result.stamped)
      assertEquals(1, result.outside)
    }
  }

  @Test
  fun aMistypedPermitIsRefusedAndNothingIsStored() = runTest {
    TestGraph.signedIn().use { fixture ->
      val scope = checkNotNull(fixture.graph.current.value)
      val old = UUID.randomUUID().toString()
      scope.outbox.append(
          listOf(Commands.contactCreate(Origin(old, 1, Events.EPOCH), Events.at(5), name = "قديم"))
      )

      val manifest = Recovery.manifest(scope, old)
      for (typo in listOf("1-1-1-1-1", "not-a-permit", "", UUID.randomUUID().toString().drop(1))) {
        assertThrows(IllegalArgumentException::class.java) {
          kotlinx.coroutines.runBlocking { Recovery.setPermit(scope, old, typo, manifest) }
        }
      }

      assertEquals(null, scope.outbox.snapshot().single().recoveryPermit)
    }
  }

  @Test
  fun thisPhonesOwnOriginNeverTakesAPermit() = runTest {
    TestGraph.signedIn().use { fixture ->
      val scope = checkNotNull(fixture.graph.current.value)

      assertThrows(IllegalArgumentException::class.java) {
        kotlinx.coroutines.runBlocking {
          Recovery.setPermit(
              scope,
              scope.account.installationId,
              UUID.randomUUID().toString(),
              ByteArray(0),
          )
        }
      }
    }
  }
}
