package com.sadad.app.data.sync

import com.sadad.app.data.db.Events
import com.sadad.ledger.Cents
import com.sadad.ledger.Commands
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerState
import com.sadad.ledger.PaymentMethod
import com.sadad.ledger.view
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncPullTest {
  @Test
  fun anIdlePassMakesExactlyOneChangesRequest() = runTest {
    SyncFixture.create().use { f ->
      f.page()
      assertEquals(SyncStatus.Idle, f.engine.pass(f.scope))
      assertEquals("/v1/mobile/changes?after=0&limit=500", f.server.takeRequest().target)
      assertEquals(2, f.server.requestCount) // One sign-in before this pass.
    }
  }

  @Test
  fun pagesSurviveAFailureAndResumeAtTheLastSavedCursor() = runTest {
    SyncFixture.create().use { f ->
      f.page(Events.page(emptyList(), 30, 10).copy(hasMore = true))
      f.page(Events.page(emptyList(), 30, 20).copy(hasMore = true))
      f.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
      assertEquals(SyncStatus.Offline, f.engine.pass(f.scope))
      assertEquals(20L, f.scope.database.metaDao().get()!!.cursor)
      repeat(3) { f.server.takeRequest() }
      f.page(Events.page(emptyList(), 30, 30))
      assertEquals(SyncStatus.Idle, f.engine.pass(f.scope))
      assertEquals("/v1/mobile/changes?after=20&limit=500", f.server.takeRequest().target)
      assertEquals(30L, f.scope.database.metaDao().get()!!.cursor)
    }
  }

  @Test
  fun anotherEpochResetsAndPullsOnceFromZero() = runTest {
    SyncFixture.create().use { f ->
      f.scope.repository.applyPage(Events.page(emptyList(), 10, 10))
      val changed = Events.page(emptyList()).copy(epoch = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
      f.page(changed)
      f.page(changed)
      assertEquals(SyncStatus.Idle, f.engine.pass(f.scope))
      assertEquals("/v1/mobile/changes?after=10&limit=500", f.server.takeRequest().target)
      assertEquals("/v1/mobile/changes?after=0&limit=500", f.server.takeRequest().target)
      assertEquals(changed.epoch, f.scope.origin()!!.epoch)
    }
  }

  @Test
  fun aMissingLeaseIsRenewedBeforePulling() = runTest {
    SyncFixture.create(fresh = false).use { f ->
      val signed = signedLease(f.signer, f.scope.account, f.now)
      f.server.enqueue(
          MockResponse.Builder()
              .body("""{"lease":{"payload":"${signed.raw}","signature":"${signed.signature}"}}""")
              .build()
      )
      f.page()
      assertEquals(SyncStatus.Idle, f.engine.pass(f.scope))
      assertEquals("/v1/mobile/lease", f.server.takeRequest().target)
      assertEquals("/v1/mobile/changes?after=0&limit=500", f.server.takeRequest().target)
      assertTrue(f.lease.allows(f.scope.account))
    }
  }

  @Test
  fun aLeaseRefusalDisablesOfflineSavesButStillPulls() = runTest {
    SyncFixture.create(fresh = false).use { f ->
      f.server.enqueue(refusalResponse("store_permission_denied"))
      f.page()
      assertEquals(SyncStatus.Idle, f.engine.pass(f.scope))
      assertFalse(f.lease.allows(f.scope.account))
    }
  }

  @Test
  fun authorizationRefusalRefreshesOnceAndRepeatsOnlyTheFailedStep() = runTest {
    SyncFixture.create().use { f ->
      f.server.enqueue(refusalResponse("authorization_required", 401))
      f.test.enqueueSession(refreshToken = "rotated")
      f.page()
      assertEquals(SyncStatus.Idle, f.engine.pass(f.scope))
      assertEquals("/v1/mobile/changes?after=0&limit=500", f.server.takeRequest().target)
      assertEquals("/auth/v1/token?grant_type=refresh_token", f.server.takeRequest().target)
      assertEquals("/v1/mobile/changes?after=0&limit=500", f.server.takeRequest().target)
      assertEquals("rotated", f.test.graph.accounts.refreshToken(f.scope.account.storeUserId))
    }
  }

  @Test
  fun multiPagePullThroughWorkingSetsEqualsTheWholeStoreFold() = runTest {
    SyncFixture.create().use { f ->
      fun id(n: Int) = "00000000-0000-4000-8000-%012d".format(n)
      val (a, b, aOwed, aOwes, bOwed) = listOf(id(1), id(2), id(11), id(12), id(13))
      val (aPaid, bPaid) = listOf(id(21), id(22))
      val o = Events.origin
      fun t(m: Int) = Events.at(m)
      val pages =
          listOf(
              listOf(
                  Commands.contactCreate(o, t(0), "أ", entityId = a),
                  Commands.contactCreate(o, t(1), "ب", entityId = b),
                  Commands.debtCreate(
                      o,
                      t(2),
                      a,
                      Direction.RECEIVABLE,
                      Cents(10_000),
                      entityId = aOwed,
                  ),
                  Commands.debtCreate(
                      o,
                      t(3),
                      a,
                      Direction.PAYABLE,
                      Cents(3_000),
                      entityId = aOwes,
                  ),
                  Commands.debtCreate(
                      o,
                      t(4),
                      b,
                      Direction.RECEIVABLE,
                      Cents(8_000),
                      entityId = bOwed,
                  ),
              ),
              listOf(
                  Commands.debtOffset(o, t(5), aOwed, aOwes, Cents(1_000), entityId = id(31)),
                  Commands.paymentCreate(
                      o,
                      t(6),
                      aOwed,
                      Cents(2_000),
                      PaymentMethod.CASH,
                      entityId = aPaid,
                  ),
                  Commands.paymentCreate(
                      o,
                      t(7),
                      bOwed,
                      Cents(1_000),
                      PaymentMethod.CASH,
                      entityId = bPaid,
                  ),
                  Commands.debtCorrect(o, t(8), bOwed, 1, Cents(9_000), "تصحيح", entityId = id(41)),
                  Commands.paymentReverse(o, t(9), bPaid, "خطأ", entityId = id(42)),
              ),
              listOf(
                  Commands.contactUpdate(o, t(10), b, 1, "ب جديد", entityId = b),
                  Commands.contactArchive(o, t(11), a, 1, "سلة", entityId = a),
              ),
              listOf(Commands.contactDelete(o, t(12), a, 2, "نهائي", entityId = a)),
          )
      var sequence = 0L
      val changes = pages.mapIndexed { i, commands ->
        Events.page(commands.map { Events.accepted(it, ++sequence) })
            .copy(hasMore = i < pages.lastIndex)
      }
      changes.forEach { f.page(it) }
      assertEquals(SyncStatus.Idle, f.engine.pass(f.scope))

      // Room and the projection both equal folding every page over the whole store.
      val full = changes.fold(LedgerState.EMPTY) { state, page -> state.apply(page) }
      assertEquals(full, f.scope.store.load())
      val expected =
          full.view(emptyList(), emptySet(), f.scope.selfInstallationId, f.scope.staffName.first())
      assertEquals(expected, f.scope.personLedger.wholeView())
      assertEquals(listOf(b), expected.contacts.map { it.id })
    }
  }

  @Test
  fun aSecondAuthorizationRefusalSignsOutWithoutAnotherRefresh() = runTest {
    SyncFixture.create().use { f ->
      f.server.enqueue(refusalResponse("authorization_required", 401))
      f.test.enqueueSession()
      f.server.enqueue(refusalResponse("authorization_required", 401))
      assertEquals(SyncStatus.SignedOut, f.engine.pass(f.scope))
      assertNull(f.test.graph.accounts.refreshToken(f.scope.account.storeUserId))
      assertEquals(4, f.server.requestCount)
    }
  }
}
