package com.sadad.app.feature.home

import androidx.lifecycle.viewModelScope
import com.sadad.app.data.db.Events
import com.sadad.app.data.session.Account
import com.sadad.app.data.session.StoreScope
import com.sadad.app.data.sync.SyncStatus
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.contacts.contactId
import com.sadad.app.feature.contacts.seedCommands
import com.sadad.app.testing.TestGraph
import com.sadad.app.testing.seedDebt
import com.sadad.app.testing.seedPayment
import com.sadad.app.ui.UiTestApplication
import com.sadad.ledger.Cents
import com.sadad.ledger.Commands
import com.sadad.ledger.ContactKind
import com.sadad.ledger.ContactView
import com.sadad.ledger.DebtView
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerView
import com.sadad.ledger.PaymentMethod
import com.sadad.ledger.PaymentView
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What Home says about the store. The two hero figures must not be the same number computed twice:
 * one is what people owe (positive `net` only), the other is what the store owes (negative `net`,
 * absolute), and a supplier's balance belongs to the second. Then the month, the follow-up list and
 * the two states that replace the page: an empty store and an offline queue.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class HomeViewModelTest {
  @get:Rule val main = MainDispatcherRule()
  private val zone: ZoneId = ZoneId.of("Asia/Jerusalem")
  private val october: YearMonth = YearMonth.of(2026, 10)
  private val today: LocalDate = LocalDate.of(2026, 10, 25)

  @Test
  fun theHeroSplitsPositiveAndNegativeBalancesIncludingSuppliers() {
    val view =
        ledger(
            contacts =
                listOf(contact("c1", "سامي"), contact("s1", "مخبز الأمل", ContactKind.SUPPLIER)),
            debts =
                listOf(
                    debt("d1", "c1", 450_000, at(2026, 10, 2)),
                    debt("d2", "c1", 798_050, at(2026, 10, 3)),
                    debt("p1", "s1", 124_000, at(2026, 10, 4), direction = Direction.PAYABLE),
                ),
        )

    val state = homeUiState(view, october, zone, today = today)

    assertEquals(
        "«لك عند الناس» sums the positive nets only",
        Cents(12_480_50),
        state.owedToStore,
    )
    assertEquals(
        "a supplier the store owes lands in «عليك للناس»",
        Cents(124_000),
        state.storeOwes,
    )
  }

  @Test
  fun aContactInCreditPullsTheirOwnRowOutOfTheOwedTotal() {
    val view =
        ledger(
            contacts = listOf(contact("c1", "سامي"), contact("c2", "ليلى")),
            debts =
                listOf(
                    debt("d1", "c1", 5_000, at(2026, 10, 2)),
                    debt("d2", "c2", 2_000, at(2026, 10, 2), direction = Direction.PAYABLE),
                ),
        )

    val state = homeUiState(view, october, zone, today = today)

    assertEquals("only سامي is owed", Cents(5_000), state.owedToStore)
    assertEquals("ليلى holds the store's money", Cents(2_000), state.storeOwes)
  }

  @Test
  fun theMonthCountsCustomersOnlyAndExcludesSupplierEntries() {
    val view =
        ledger(
            contacts =
                listOf(contact("c1", "سامي"), contact("s1", "مخبز الأمل", ContactKind.SUPPLIER)),
            debts =
                listOf(
                    debt("d1", "c1", 3_200, at(2026, 10, 2)),
                    debt("p1", "s1", 900_000, at(2026, 10, 5), direction = Direction.PAYABLE),
                ),
            payments =
                listOf(
                    payment("pay1", "d1", "c1", 1_950, at(2026, 10, 6)),
                    payment("pay2", "p1", "s1", 500_000, at(2026, 10, 7)),
                ),
        )

    val state = homeUiState(view, october, zone, today = today)

    assertEquals(Cents(3_200), state.monthDebts)
    assertEquals(Cents(1_950), state.monthPayments)
    assertEquals("the supplier payment is not one of the month's", 1, state.monthPaymentsCount)
    assertEquals(61, state.collectionPercent)
  }

  @Test
  fun theCollectionShareCapsAtOneHundredPercent() {
    val view =
        ledger(
            debts = listOf(debt("d1", "c1", 1_000, at(2026, 10, 2))),
            payments = listOf(payment("p1", "d1", "c1", 2_000, at(2026, 10, 3))),
        )

    val state = homeUiState(view, october, zone, today = today)

    assertEquals(100, state.collectionPercent)
  }

  @Test
  fun aMonthWithoutDebtsHasNoShareRatherThanAFullBar() {
    val view = ledger(debts = emptyList(), payments = emptyList())

    val state = homeUiState(view, october, zone, today = today)

    assertEquals(0, state.collectionPercent)
  }

  @Test
  fun followUpListsOverdueBeforeDueTodayAndStopsAtFiveRows() {
    val view =
        ledger(
            contacts =
                listOf(
                    contact("c1", "مريم حسن"),
                    contact("c2", "أحمد سالم"),
                    contact("c3", "خالد يوسف"),
                    contact("c4", "سارة محمود"),
                    contact("c5", "أبو رامي"),
                    contact("c6", "ليلى"),
                    contact("c7", "ناصر"),
                ),
            debts =
                listOf(
                    debt("d1", "c1", 112_000, at(2026, 10, 1), due = "2026-10-22"),
                    debt("d2", "c2", 25_000, at(2026, 10, 1), due = "2026-10-25"),
                    debt("d3", "c3", 8_500, at(2026, 10, 1), due = "2026-10-25"),
                    debt("d4", "c4", 5_000, at(2026, 10, 1), due = "2026-10-20"),
                    debt("d5", "c5", 4_000, at(2026, 10, 1), due = "2026-10-24"),
                    debt("d6", "c6", 3_000, at(2026, 10, 1), due = "2026-10-25"),
                    debt("d7", "c7", 2_000, at(2026, 10, 1), due = "2026-11-10"),
                ),
        )

    val state = homeUiState(view, october, zone, today = today)

    assertEquals("the count is every person who needs chasing", 6, state.followUpCount)
    assertEquals("five rows at most", 5, state.followUps.size)
    assertEquals(
        "most days late first",
        listOf("سارة محمود", "مريم حسن", "أبو رامي"),
        state.followUps.take(3).map { it.name },
    )
    assertEquals(
        "five days, three days, one day — then the two due today",
        listOf(5, 3, 1),
        state.followUps.take(3).map { it.lateDays },
    )
    assertTrue(
        "a debt due next month is not chased",
        state.followUps.none { it.name == "ناصر" },
    )
    assertEquals("the row shows what they owe", Cents(112_000), state.followUps[1].amount)
    assertEquals("due today has no late day", null, state.followUps[3].lateDays)
  }

  @Test
  fun followUpSkipsPeopleWhoDoNotOweAnything() {
    val view =
        ledger(
            contacts = listOf(contact("c1", "سامي")),
            debts =
                listOf(debt("d1", "c1", 5_000, at(2026, 10, 1), due = "2026-10-01", remaining = 0)),
            payments = listOf(payment("p1", "d1", "c1", 5_000, at(2026, 10, 2))),
        )

    val state = homeUiState(view, october, zone, today = today)

    assertTrue("a settled contact is not chased", state.followUps.isEmpty())
    assertEquals(0, state.followUpCount)
  }

  @Test
  fun aStoreWithNoContactsAndNoEntriesIsEmpty() {
    val state = homeUiState(ledger(contacts = emptyList()), october, zone, today = today)

    assertTrue(state.empty)

    val withOneContact =
        homeUiState(ledger(contacts = listOf(contact("c1", "سامي"))), october, zone, today = today)
    assertTrue("a contact alone ends the empty state", !withOneContact.empty)
  }

  @Test
  fun offlineWithThreeQueuedEntriesReportsTheBannerAndTheLeaseEnd() {
    val leaseEnd = at(2026, 10, 25).plusSeconds(4 * 3600)
    val state =
        homeUiState(
            ledger(),
            october,
            zone,
            today = today,
            queued = 3,
            offline = true,
            leaseEndsAt = leaseEnd,
        )

    assertEquals(3, state.offline?.queued)
    assertEquals(leaseEnd, state.offline?.leaseEndsAt)
  }

  @Test
  fun nothingWaitingAndOnlineMeansNoBanner() {
    val state = homeUiState(ledger(), october, zone, today = today, queued = 0, offline = true)
    assertNull("offline with nothing queued is not the banner", state.offline)

    val online = homeUiState(ledger(), october, zone, today = today, queued = 4, offline = false)
    assertNull(online.offline)
  }

  @Test
  fun refreshAsksTheSourceOnceAndRefreshingFollowsRunningToIdle() = runTest {
    TestGraph.signedIn().use { fixture ->
      val status = MutableSharedFlow<SyncStatus>(replay = 1).apply { tryEmit(SyncStatus.Idle) }
      val refreshed = AtomicInteger()
      val real = fixture.graph.homeSource()
      val source =
          object : HomeSource by real {
            override fun syncStatus(storeUserId: String) = status

            override fun offlineUntil(account: Account): Long = LEASE_END.toEpochMilli()

            override fun refresh() {
              refreshed.incrementAndGet()
            }

            override fun summaryReady(scope: StoreScope) = flowOf(false)

            override fun view(scope: StoreScope) = scope.repository.view

            override fun roomData(
                scope: StoreScope,
                month: YearMonth,
                zone: ZoneId,
                today: LocalDate,
            ): Flow<HomeRoomData> = flow {
              throw AssertionError("roomData must not be read while summaryReady is false")
            }
          }
      val model =
          HomeViewModel(source) { view, month, zone, today ->
                homeUiState(view = view, month = month, zone = zone, today = today)
              }
              .also { fixture.closeWithStore(it.viewModelScope) }
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
      model.state.first { it.loaded }

      model.refresh()
      assertEquals("one pull, one pass", 1, refreshed.get())
      assertFalse(model.state.value.refreshing)

      status.emit(SyncStatus.Running)
      model.state.first { it.refreshing }
      status.emit(SyncStatus.Idle)
      assertFalse(model.state.first { !it.refreshing }.refreshing)
    }
  }

  @Test
  fun roomRowsGiveTotalsFollowUpAndMonthWithoutTheView() = runTest {
    TestGraph.signedIn().use { fixture ->
      val store = checkNotNull(fixture.graph.current.value)
      val a = contactId(1)
      val b = contactId(2)
      val supplier = contactId(3)
      val dA = contactId(11)
      val dB = contactId(12)
      val dSupplier = contactId(13)
      val paymentA = contactId(21)
      fixture.seedCommands(
          listOf(
              Commands.contactCreate(Events.origin, at(2026, 10, 1), name = "سامي", entityId = a),
              Commands.contactCreate(Events.origin, at(2026, 10, 1), name = "ليلى", entityId = b),
              Commands.contactCreate(
                  Events.origin,
                  at(2026, 10, 1),
                  name = "مخبز الأمل",
                  kind = ContactKind.SUPPLIER,
                  entityId = supplier,
              ),
              Commands.debtCreate(
                  Events.origin,
                  at(2026, 10, 2),
                  contactId = a,
                  direction = Direction.RECEIVABLE,
                  amount = Cents(10_000),
                  dueDate = "2026-10-22",
                  entityId = dA,
              ),
              Commands.debtCreate(
                  Events.origin,
                  at(2026, 10, 2),
                  contactId = b,
                  direction = Direction.RECEIVABLE,
                  amount = Cents(4_000),
                  dueDate = "2026-10-25",
                  entityId = dB,
              ),
              Commands.debtCreate(
                  Events.origin,
                  at(2026, 10, 3),
                  contactId = supplier,
                  direction = Direction.PAYABLE,
                  amount = Cents(7_000),
                  entityId = dSupplier,
              ),
              Commands.paymentCreate(
                  Events.origin,
                  at(2026, 10, 5),
                  debtId = dA,
                  amount = Cents(3_000),
                  method = PaymentMethod.CASH,
                  entityId = paymentA,
              ),
          )
      )
      val view = store.repository.view.first { it != null && it.contacts.size == 3 }!!
      val expected = homeUiState(view, october, zone, today = today)

      val clock = MovableClock(at(2026, 10, 25), zone)
      val model = fixture.roomHome(clock)
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
      val state = model.state.first { it.loaded }

      assertEquals(expected.owedToStore, state.owedToStore)
      assertEquals(expected.storeOwes, state.storeOwes)
      assertEquals(expected.monthDebts, state.monthDebts)
      assertEquals(expected.monthPayments, state.monthPayments)
      assertEquals(expected.monthPaymentsCount, state.monthPaymentsCount)
      assertEquals(expected.collectionPercent, state.collectionPercent)
      assertEquals(expected.followUps, state.followUps)
      assertEquals(expected.followUpCount, state.followUpCount)
      assertEquals(expected.empty, state.empty)
      assertTrue(state.loaded)
    }
  }

  @Test
  fun queuedDebtAndQueuedPaymentInTheMonthEqualTheWholeStoreView() = runTest {
    TestGraph.signedIn().use { fixture ->
      val store = checkNotNull(fixture.graph.current.value)
      val a = contactId(1)
      val dA = contactId(11)
      fixture.seedCommands(
          listOf(
              Commands.contactCreate(Events.origin, at(2026, 10, 1), name = "سامي", entityId = a),
              Commands.debtCreate(
                  Events.origin,
                  at(2026, 10, 16),
                  contactId = a,
                  direction = Direction.RECEIVABLE,
                  amount = Cents(10_000),
                  dueDate = "2026-10-20",
                  entityId = dA,
              ),
          )
      )
      store.seedDebt(a, amount = Cents(3_000), dueDate = "2026-10-20")
      store.seedPayment(dA, Cents(2_000))
      val view =
          store.repository.view.first {
            it != null && it.debts.any { debt -> debt.pending } && it.payments.any { it.pending }
          }!!
      // The queued rows are created at Instant.now() (November), so the month is November.
      val november = YearMonth.of(2026, 11)
      val novemberToday = LocalDate.of(2026, 11, 7)
      val expected = homeUiState(view, november, zone, today = novemberToday)
      // Wait for the summary collector to rewrite the row, so the room totals include the queue.
      val deadline = System.currentTimeMillis() + 5_000
      while (System.currentTimeMillis() < deadline) {
        val rows = store.database.contactSummaryDao().all()
        if (rows.any { it.contactId == a && it.net == 11_000L }) break
        Thread.sleep(10)
      }
      assertTrue(
          store.database.contactSummaryDao().all().any { it.contactId == a && it.net == 11_000L }
      )

      val clock = MovableClock(at(2026, 11, 7), zone)
      val model = fixture.roomHome(clock)
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
      val state = model.state.first { it.loaded && it.owedToStore == expected.owedToStore }

      assertEquals(expected.owedToStore, state.owedToStore)
      assertEquals(expected.storeOwes, state.storeOwes)
      assertEquals(expected.monthDebts, state.monthDebts)
      assertEquals(expected.monthPayments, state.monthPayments)
      assertEquals(expected.monthPaymentsCount, state.monthPaymentsCount)
      assertEquals(expected.collectionPercent, state.collectionPercent)
      assertEquals(expected.followUps, state.followUps)
      assertEquals(expected.followUpCount, state.followUpCount)
      assertEquals(expected.empty, state.empty)
    }
  }

  @Test
  fun whileSummaryIsNotReadyHomeShowsTheViewAndNeverAnEmptyStore() = runTest {
    TestGraph.signedIn().use { fixture ->
      val store = checkNotNull(fixture.graph.current.value)
      val a = contactId(1)
      val dA = contactId(11)
      fixture.seedCommands(
          listOf(
              Commands.contactCreate(Events.origin, at(2026, 10, 1), name = "سامي", entityId = a),
              Commands.debtCreate(
                  Events.origin,
                  at(2026, 10, 2),
                  contactId = a,
                  direction = Direction.RECEIVABLE,
                  amount = Cents(5_000),
                  entityId = dA,
              ),
          )
      )
      val view = store.repository.view.first { it != null && it.contacts.size == 1 }!!
      val expected = homeUiState(view, october, zone, today = today)
      // The fill wait: the summary table is still empty, but the ledger is full.
      store.database.contactSummaryDao().clear()

      val clock = MovableClock(at(2026, 10, 25), zone)
      val model = fixture.home(MutableStateFlow(SyncStatus.Idle), AtomicInteger(), clock)
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
      val state = model.state.first { it.loaded }

      assertEquals(expected.owedToStore, state.owedToStore)
      assertEquals(expected.monthDebts, state.monthDebts)
      assertFalse("a full ledger never reads as an empty store", state.empty)
    }
  }

  @Test
  fun roomStateEqualsHomeUiStateAtThreeDifferentDays() = runTest {
    TestGraph.signedIn().use { fixture ->
      val store = checkNotNull(fixture.graph.current.value)
      val a = contactId(1)
      val b = contactId(2)
      val c = contactId(3)
      val dA = contactId(11)
      val dB = contactId(12)
      val dC = contactId(13)
      fixture.seedCommands(
          listOf(
              Commands.contactCreate(Events.origin, at(2026, 10, 1), name = "سامي", entityId = a),
              Commands.contactCreate(Events.origin, at(2026, 10, 1), name = "ليلى", entityId = b),
              Commands.contactCreate(Events.origin, at(2026, 10, 1), name = "كريم", entityId = c),
              Commands.debtCreate(
                  Events.origin,
                  at(2026, 10, 2),
                  contactId = a,
                  direction = Direction.RECEIVABLE,
                  amount = Cents(10_000),
                  dueDate = "2026-10-20",
                  entityId = dA,
              ),
              Commands.debtCreate(
                  Events.origin,
                  at(2026, 10, 2),
                  contactId = b,
                  direction = Direction.RECEIVABLE,
                  amount = Cents(4_000),
                  dueDate = "2026-10-25",
                  entityId = dB,
              ),
              Commands.debtCreate(
                  Events.origin,
                  at(2026, 10, 2),
                  contactId = c,
                  direction = Direction.RECEIVABLE,
                  amount = Cents(6_000),
                  dueDate = "2026-10-10",
                  entityId = dC,
              ),
          )
      )
      val view = store.repository.view.first { it != null && it.contacts.size == 3 }!!

      for (day in
          listOf(
              LocalDate.of(2026, 10, 19),
              LocalDate.of(2026, 10, 20),
              LocalDate.of(2026, 10, 25),
          )) {
        val expected = homeUiState(view, october, zone, today = day)
        val clock = MovableClock(day.atTime(12, 0).atZone(zone).toInstant(), zone)
        val model = fixture.roomHome(clock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        val state = model.state.first { it.loaded }

        assertEquals("owed on $day", expected.owedToStore, state.owedToStore)
        assertEquals("owes on $day", expected.storeOwes, state.storeOwes)
        assertEquals("month debts on $day", expected.monthDebts, state.monthDebts)
        assertEquals("month payments on $day", expected.monthPayments, state.monthPayments)
        assertEquals(
            "month payments count on $day",
            expected.monthPaymentsCount,
            state.monthPaymentsCount,
        )
        assertEquals("collection on $day", expected.collectionPercent, state.collectionPercent)
        assertEquals("follow-ups on $day", expected.followUps, state.followUps)
        assertEquals("follow-up count on $day", expected.followUpCount, state.followUpCount)
        assertEquals("empty on $day", expected.empty, state.empty)
      }
    }
  }

  /* ------------------------------------------------------------------ builders */

  @Test
  fun beforeTheLedgerIsReadTheStateIsNotLoadedAndAfterItIs() {
    val source =
        object : HomeSource {
          override val current = flowOf<StoreScope?>(null)
          override val accounts = flowOf(emptyList<Account>())

          override fun syncStatus(storeUserId: String) = flowOf<SyncStatus>(SyncStatus.Idle)

          override fun offlineUntil(account: Account): Long? = null

          override fun refresh() {}

          override fun summaryReady(scope: StoreScope): Flow<Boolean> = flow {
            throw AssertionError("no store, no summaryReady")
          }

          override fun view(scope: StoreScope): Flow<LedgerView?> = flow {
            throw AssertionError("no store, no view")
          }

          override fun roomData(
              scope: StoreScope,
              month: YearMonth,
              zone: ZoneId,
              today: LocalDate,
          ): Flow<HomeRoomData> = flow {
            throw AssertionError("no store, no roomData")
          }
        }
    assertFalse(HomeViewModel(source).state.value.loaded)

    val state =
        homeUiState(
            ledger(debts = listOf(debt("d1", "c1", 5_000, at(2026, 10, 2)))),
            october,
            zone,
            today = today,
        )
    assertTrue(state.loaded)
    assertEquals(Cents(5_000), state.owedToStore)
  }

  @Test
  fun aSyncStatusFlipOrAQueuedRowOnlyChangesTheBannerNotTheTotals() = runTest {
    TestGraph.signedIn().use { fixture ->
      val store = checkNotNull(fixture.graph.current.value)
      val status = MutableSharedFlow<SyncStatus>(replay = 1).apply { tryEmit(SyncStatus.Idle) }
      val calls = AtomicInteger()
      val model = fixture.home(status, calls)
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
      val contact =
          store.repository
              .addContact("سامي", null, null, null, null, ContactKind.CUSTOMER)
              .getOrThrow()
      store.seedDebt(contact, amount = Cents(5_000))
      model.state.first { it.owedToStore == Cents(5_000) }
      val derived = calls.get()

      repeat(3) {
        status.emit(SyncStatus.Running)
        status.emit(SyncStatus.Idle)
      }
      status.emit(SyncStatus.Offline)
      assertEquals(
          HomeOffline(2, LEASE_END),
          model.state.first { it.offline?.queued == 2 }.offline,
      )
      status.emit(SyncStatus.Idle)
      model.state.first { it.offline == null }

      assertEquals("only the banner moved", derived, calls.get())
      assertEquals(Cents(5_000), model.state.value.owedToStore)
    }
  }

  @Test
  fun aNewViewOrANewDayDerivesTheTotalsAgain() = runTest {
    TestGraph.signedIn().use { fixture ->
      val store = checkNotNull(fixture.graph.current.value)
      val clock = MovableClock(Instant.parse("2026-10-25T08:00:00Z"), zone)
      val calls = AtomicInteger()
      val model = fixture.home(MutableStateFlow(SyncStatus.Idle), calls, clock)
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
      model.state.first { it.loaded }
      val first = calls.get()

      store.repository.addContact("هدى", null, null, null, null, ContactKind.CUSTOMER).getOrThrow()
      model.state.first { !it.empty }
      val afterView = calls.get()
      assertTrue("a new view derives again", afterView > first)

      clock.now = clock.now.plus(Duration.ofDays(1))
      val follow = launch { model.followMonth() }
      runCurrent()
      follow.cancel()
      awaitCalls(calls, afterView + 1)
    }
  }

  // The summaryReady=false path (the fill wait): the view is the only totals source.
  private fun TestGraph.home(
      status: Flow<SyncStatus>,
      calls: AtomicInteger,
      clock: Clock? = null,
  ): HomeViewModel {
    val real = graph.homeSource()
    val source =
        object : HomeSource by real {
          override fun syncStatus(storeUserId: String) = status

          override fun offlineUntil(account: Account): Long = LEASE_END.toEpochMilli()

          override fun refresh() {}

          override fun summaryReady(scope: StoreScope) = flowOf(false)

          override fun view(scope: StoreScope) = scope.repository.view

          override fun roomData(
              scope: StoreScope,
              month: YearMonth,
              zone: ZoneId,
              today: LocalDate,
          ): Flow<HomeRoomData> = flow {
            throw AssertionError("roomData must not be read while summaryReady is false")
          }
        }
    return HomeViewModel(source, clock) { view, month, zone, today ->
          calls.incrementAndGet()
          homeUiState(view = view, month = month, zone = zone, today = today)
        }
        .also { closeWithStore(it.viewModelScope) }
  }

  // The room path: the whole-store view and the derivation must never be touched.
  private fun TestGraph.roomHome(clock: Clock): HomeViewModel {
    val real = graph.homeSource()
    val source =
        object : HomeSource by real {
          override fun view(scope: StoreScope): Flow<LedgerView?> = flow {
            throw AssertionError("repository.view must not be collected after summaryReady")
          }
        }
    return HomeViewModel(source, clock) { _, _, _, _ ->
          throw AssertionError("derive must not run after summaryReady")
        }
        .also { closeWithStore(it.viewModelScope) }
  }

  /** The derivation runs on another thread; polling keeps the assertion about the count. */
  private fun awaitCalls(calls: AtomicInteger, count: Int) {
    val deadline = System.currentTimeMillis() + 5_000
    while (calls.get() < count && System.currentTimeMillis() < deadline) Thread.sleep(10)
    assertEquals(count, calls.get())
  }

  private class MovableClock(@Volatile var now: Instant, private val zone: ZoneId) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MovableClock(now, zone)

    override fun instant(): Instant = now
  }

  private fun at(year: Int, month: Int, day: Int): Instant =
      LocalDate.of(year, month, day).atTime(12, 0).atZone(zone).toInstant()

  private fun contact(
      id: String,
      name: String,
      kind: ContactKind = ContactKind.CUSTOMER,
  ): ContactView =
      ContactView(
          id = id,
          name = name,
          phone = "05****0000",
          category = "",
          note = "",
          version = 1,
          pending = false,
          createdAt = at(2026, 9, 1),
          createdBy = "",
          operationId = "op-$id",
          kind = kind,
      )

  private fun debt(
      id: String,
      contactId: String,
      amount: Long,
      createdAt: Instant,
      direction: Direction = Direction.RECEIVABLE,
      pending: Boolean = false,
      due: String? = null,
      remaining: Long? = null,
  ): DebtView =
      DebtView(
          id = id,
          contactId = contactId,
          direction = direction,
          amount = Cents(amount),
          paid = Cents.ZERO,
          confirmedRemaining = Cents(remaining ?: amount),
          remaining = Cents(remaining ?: amount),
          version = 1,
          pending = pending,
          hasPendingPayments = false,
          note = "",
          dueDate = due,
          createdAt = createdAt,
          createdBy = "",
          operationId = "op-$id",
      )

  private fun payment(
      id: String,
      debtId: String,
      contactId: String,
      amount: Long,
      createdAt: Instant,
      reversed: Boolean = false,
  ): PaymentView =
      PaymentView(
          id = id,
          debtId = debtId,
          contactId = contactId,
          amount = Cents(amount),
          method = PaymentMethod.CASH,
          note = "",
          reversed = reversed,
          pending = false,
          createdAt = createdAt,
          createdBy = "",
          operationId = "op-$id",
      )

  private fun ledger(
      contacts: List<ContactView> = listOf(contact("c1", "سامي")),
      debts: List<DebtView> = emptyList(),
      payments: List<PaymentView> = emptyList(),
  ): LedgerView =
      LedgerView(
          contacts = contacts,
          archivedContacts = emptyList(),
          debts = debts,
          payments = payments,
          receivable = Cents.ZERO,
          payable = Cents.ZERO,
          confirmedReceivable = Cents.ZERO,
          confirmedPayable = Cents.ZERO,
          pendingContacts = 0,
          pendingDebts = 0,
          pendingPayments = 0,
          excludedOperations = emptySet(),
      )

  private companion object {
    val LEASE_END: Instant = Instant.parse("2026-10-25T17:00:00Z")
  }
}
