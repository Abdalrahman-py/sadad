package com.sadad.app.data.sync

import com.sadad.app.data.db.Events
import com.sadad.ledger.Cents
import com.sadad.ledger.Commands
import com.sadad.ledger.Direction
import com.sadad.ledger.PaymentMethod
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
  fun multiPagePullViewEqualsWholeStoreView() = runTest {
    SyncFixture.create().use { f ->
      val contactId = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
      val debtId = "b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e"
      val paymentId = "c3d4e5f6-a7b8-4c9d-8e1f-2a3b4c5d6e7f"
      val contact =
          Commands.contactCreate(Events.origin, Events.at(0), "سامي", entityId = contactId)
      val debt =
          Commands.debtCreate(
              Events.origin,
              Events.at(1),
              contactId,
              Direction.RECEIVABLE,
              Cents(10_000),
              entityId = debtId,
          )
      val payment =
          Commands.paymentCreate(
              Events.origin,
              Events.at(2),
              debtId,
              Cents(3_000),
              PaymentMethod.CASH,
              entityId = paymentId,
          )
      f.page(
          Events.page(listOf(Events.accepted(contact, 1), Events.accepted(debt, 2)))
              .copy(hasMore = true)
      )
      f.page(Events.page(listOf(Events.accepted(payment, 3))))
      assertEquals(SyncStatus.Idle, f.engine.pass(f.scope))
      val view = f.scope.repository.view.first { it != null }!!
      assertEquals(listOf(contactId), view.contacts.map { it.id })
      assertEquals(debtId, view.debts.single().id)
      assertEquals(Cents(10_000), view.debts.single().amount)
      assertEquals(paymentId, view.payments.single().id)
      assertEquals(Cents(7_000), view.receivable)
      assertEquals(3L, f.scope.database.metaDao().get()!!.cursor)
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
