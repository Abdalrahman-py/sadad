package com.sadad.app.data.sync

import com.sadad.app.data.crypto.SoftwareSigner
import com.sadad.app.data.db.Events
import com.sadad.app.data.ledger.receiptOf
import com.sadad.app.data.net.LeaseVerifier
import com.sadad.app.testing.SyncSettings
import com.sadad.app.testing.TestGraph
import com.sadad.ledger.Changes
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerJson
import com.sadad.ledger.Receipt
import com.sadad.ledger.ReceiptStatus
import java.util.concurrent.TimeUnit
import kotlinx.serialization.builtins.ListSerializer
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient

/**
 * The graph's own sync engine and lease store, on a clock and boot count the test moves and a lease
 * key it signs with. The client fails fast: no silent retry, a 3 s read timeout.
 */
internal class SyncFixture
private constructor(val test: TestGraph, val signer: SoftwareSigner, private val clock: Clock) :
    AutoCloseable {
  /** What the graph's lease store reads; a test sets [now] and [boot]. */
  class Clock {
    var boot = 5
    var now = System.currentTimeMillis()
  }

  val scope
    get() = test.graph.current.value!!

  var boot by clock::boot
  var now by clock::now

  val lease: LeaseStore
    get() = test.graph.leaseFor(scope.account.storeUserId)

  val engine
    get() = test.graph.syncEngine

  val server
    get() = test.server

  suspend fun queue(count: Int, note: String = ""): List<LedgerCommand> {
    repeat(count) { scope.repository.addContact("شخص $it", null, null, note, null).getOrThrow() }
    return scope.outbox.queued(Int.MAX_VALUE)
  }

  fun page(page: Changes = Events.page(emptyList())) {
    server.enqueue(pageResponse(page))
  }

  suspend fun freshLease() {
    lease.save(scope.account, signedLease(signer, scope.account, now))
  }

  override fun close() {
    test.graph.client.connectionPool.evictAll()
    test.close()
  }

  companion object {
    suspend fun create(fresh: Boolean = true): SyncFixture {
      val signer = SoftwareSigner("lease-server")
      val clock = Clock()
      val client =
          OkHttpClient.Builder()
              .retryOnConnectionFailure(false)
              .readTimeout(3, TimeUnit.SECONDS)
              .build()
      val sync =
          SyncSettings(
              client,
              LeaseVerifier(signer.publicKey.x, signer.publicKey.y),
              { clock.boot },
              { clock.now },
          )
      val fixture = SyncFixture(TestGraph.signedIn(sync = sync), signer, clock)
      fixture.server.takeRequest()
      if (fresh) fixture.freshLease()
      return fixture
    }
  }
}

internal fun pageResponse(page: Changes): MockResponse =
    MockResponse.Builder()
        .body("""{"changes":${LedgerJson.encodeToString(Changes.serializer(), page)}}""")
        .build()

internal fun receiptResponse(commands: List<LedgerCommand>, reject: Int = -1): MockResponse {
  val receipts = commands.mapIndexed { index, command ->
    receiptOf(
        command,
        if (index == reject) ReceiptStatus.REJECTED else ReceiptStatus.ACCEPTED,
        if (index == reject) "store_permission_denied" else null,
        index + 1L,
    )
  }
  return MockResponse.Builder()
      .body(
          """{"receipts":${LedgerJson.encodeToString(ListSerializer(Receipt.serializer()), receipts)}}"""
      )
      .build()
}

internal fun refusalResponse(code: String, status: Int = 403): MockResponse =
    MockResponse.Builder().code(status).body("""{"code":"$code"}""").build()
