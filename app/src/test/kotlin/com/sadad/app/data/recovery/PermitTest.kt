package com.sadad.app.data.recovery

import com.sadad.app.data.db.Events
import com.sadad.app.testing.TestGraph
import com.sadad.ledger.Commands
import com.sadad.ledger.Origin
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

      Recovery.setPermit(scope, old, "  ${permit.uppercase()} ")

      assertEquals(permit, scope.outbox.snapshot().single().recoveryPermit)
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

      for (typo in listOf("1-1-1-1-1", "not-a-permit", "", UUID.randomUUID().toString().drop(1))) {
        assertThrows(IllegalArgumentException::class.java) {
          kotlinx.coroutines.runBlocking { Recovery.setPermit(scope, old, typo) }
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
          Recovery.setPermit(scope, scope.account.installationId, UUID.randomUUID().toString())
        }
      }
    }
  }
}
