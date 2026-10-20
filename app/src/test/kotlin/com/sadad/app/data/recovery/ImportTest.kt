package com.sadad.app.data.recovery

import com.sadad.app.data.ledger.OutboxState
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.STORE_B
import com.sadad.app.testing.TestGraph
import com.sadad.ledger.ContactCreate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImportTest {
  private val passphrase = "correct horse battery".toCharArray()

  @Test
  fun aCopyOfStoreAAppendsItsCommandsOnceWithTheirOriginalOrigin() = runTest {
    val first = TestGraph.signedIn(STORE_A)
    val exported: ByteArray
    val origin: String
    try {
      first.seed {
        addContact("سامي", null, null, null, null).getOrThrow()
        addContact("ليلى", null, null, null, null).getOrThrow()
      }
      origin = checkNotNull(first.graph.current.value).account.installationId
      exported = Recovery.export(checkNotNull(first.graph.current.value), passphrase.copyOf())
    } finally {
      first.close()
    }
    assertTrue(first.context.deleteDatabase("ledger-$STORE_A.db"))

    TestGraph.signedIn(STORE_A).use { second ->
      val scope = checkNotNull(second.graph.current.value)
      assertNotEquals(origin, scope.account.installationId)

      assertEquals(2, Recovery.import(scope, exported, passphrase.copyOf()))

      val rows = scope.outbox.snapshot()
      assertEquals(2, rows.size)
      assertTrue(rows.all { it.state == OutboxState.QUEUED })
      assertEquals(setOf(origin), rows.map { it.command.originInstallationId }.toSet())
      assertEquals(
          setOf("سامي", "ليلى"),
          rows.map { (it.command.payload() as ContactCreate).name }.toSet(),
      )

      assertEquals(0, Recovery.import(scope, exported, passphrase.copyOf()))
      assertEquals(2, scope.outbox.snapshot().size)
    }
  }

  @Test
  fun aCopyFromAnotherStoreIsRefused() = runTest {
    val other = TestGraph.signedIn(STORE_B)
    val exported: ByteArray
    try {
      other.seed { addContact("متجر آخر", null, null, null, null).getOrThrow() }
      exported = Recovery.export(checkNotNull(other.graph.current.value), passphrase.copyOf())
    } finally {
      other.close()
    }

    TestGraph.signedIn(STORE_A).use { fixture ->
      val scope = checkNotNull(fixture.graph.current.value)

      val failure = runCatching {
        Recovery.import(scope, exported, passphrase.copyOf())
      }
          .exceptionOrNull()

      assertTrue(failure is IllegalArgumentException)
      assertEquals("ملف الاسترداد لمتجر أو مشروع مختلف.", failure!!.message)
      assertTrue(scope.outbox.snapshot().isEmpty())
    }
  }
}
