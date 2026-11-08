package com.sadad.app.feature.home

import com.sadad.app.AppGraph
import com.sadad.app.data.ledger.OutboxState
import com.sadad.app.data.ledger.personOf
import com.sadad.app.data.session.Account
import com.sadad.app.data.session.StoreScope
import com.sadad.app.data.sync.SyncStatus
import com.sadad.ledger.Cents
import com.sadad.ledger.DebtCreate
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerView
import com.sadad.ledger.PaymentCreate
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** One follow-up candidate from `contact_summary`: who owes, and their earliest open due date. */
data class HomeFollowUpCandidate(
    val contactId: String,
    val name: String,
    val earliestOpenDue: String,
    val net: Cents,
)

/** Everything Home draws from Room for one month and day, queued entries included. */
data class HomeRoomData(
    val owed: Cents,
    val owes: Cents,
    val monthDebts: Cents,
    val monthPayments: Cents,
    val monthPaymentsCount: Int,
    val followUpCandidates: List<HomeFollowUpCandidate>,
    val empty: Boolean,
)

/** What Home reads: the open store, the saved accounts, a store's sync state and its lease. */
interface HomeSource {
  val current: Flow<StoreScope?>
  val accounts: Flow<List<Account>>

  fun syncStatus(storeUserId: String): Flow<SyncStatus>

  /** When the store's offline lease ends, in epoch millis; null without one. */
  fun offlineUntil(account: Account): Long?

  /** The pull to refresh on Home starts one pass for the open store. */
  fun refresh()

  /** True once the summary table is filled (or needed no fill). */
  fun summaryReady(scope: StoreScope): Flow<Boolean>

  /** The whole-store view, read only while [summaryReady] is false. */
  fun view(scope: StoreScope): Flow<LedgerView?>

  /** Totals, month and follow-up from the summary table and the confirmed rows, plus the queue. */
  fun roomData(
      scope: StoreScope,
      month: YearMonth,
      zone: ZoneId,
      today: LocalDate,
  ): Flow<HomeRoomData>
}

fun AppGraph.homeSource(): HomeSource {
  val graph = this
  return object : HomeSource {
    override val current = graph.current
    override val accounts = graph.accounts.accounts

    override fun syncStatus(storeUserId: String) = graph.syncStatus(storeUserId)

    override fun offlineUntil(account: Account) = graph.offlineUntil(account)

    override fun refresh() {
      graph.current.value?.account?.storeUserId?.let(graph::refresh)
    }

    override fun summaryReady(scope: StoreScope) = scope.repository.summaryReady

    override fun view(scope: StoreScope) = scope.repository.view

    override fun roomData(
        scope: StoreScope,
        month: YearMonth,
        zone: ZoneId,
        today: LocalDate,
    ): Flow<HomeRoomData> = homeRoomData(scope, month, zone, today)
  }
}

private data class MonthAmounts(val debts: Cents, val payments: Cents, val count: Int)

private fun homeRoomData(
    scope: StoreScope,
    month: YearMonth,
    zone: ZoneId,
    today: LocalDate,
): Flow<HomeRoomData> {
  val start = month.atDay(1).atStartOfDay(zone).toInstant()
  val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
  val from = start.toEpochMilli()
  val to = end.toEpochMilli()
  val summaries = scope.database.contactSummaryDao()
  val owed = summaries.owedTotal().map { Cents(it) }
  val owes = summaries.owesTotal().map { Cents(it) }
  val empty = summaries.observeCount().map { it == 0 }
  val followUps =
      summaries.followUpCandidates(today.toString()).map { rows ->
        rows.map {
          HomeFollowUpCandidate(it.contactId, it.name, it.earliestOpenDue!!, Cents(it.net))
        }
      }
  // One subscription to the queued part: the three month figures share it.
  val confirmedMonth =
      combine(
          scope.database.debtDao().monthReceivableDebts(from, to).map { Cents(it) },
          scope.database.paymentDao().monthReceivablePayments(from, to).map { Cents(it) },
          scope.database.paymentDao().monthReceivablePaymentsCount(from, to),
      ) { debts, payments, count ->
        Triple(debts, payments, count)
      }
  val monthAmounts =
      combine(confirmedMonth, queuedMonth(scope, start, end)) { confirmed, pending ->
        Triple(
            confirmed.first + pending.debts,
            confirmed.second + pending.payments,
            confirmed.third + pending.count,
        )
      }
  val totals =
      combine(owed, owes, empty) { owedTotal, owesTotal, isEmpty ->
        Triple(owedTotal, owesTotal, isEmpty)
      }
  return combine(totals, monthAmounts, followUps) { totals, monthAmounts, candidates ->
    HomeRoomData(
        owed = totals.first,
        owes = totals.second,
        monthDebts = monthAmounts.first,
        monthPayments = monthAmounts.second,
        monthPaymentsCount = monthAmounts.third,
        followUpCandidates = candidates,
        empty = totals.third,
    )
  }
}

/**
 * The month's queued part: for the people with unsettled outbox rows, their [PersonLedger] view's
 * pending RECEIVABLE debts and pending non-reversed payments on RECEIVABLE debts in `[from, to)`.
 */
private fun queuedMonth(scope: StoreScope, from: Instant, to: Instant): Flow<MonthAmounts> {
  val zero = MonthAmounts(Cents.ZERO, Cents.ZERO, 0)
  return scope.outbox.rows.flatMapLatest { rows ->
    val pending = rows.filter { it.state == OutboxState.QUEUED || it.state == OutboxState.ACCEPTED }
    if (pending.isEmpty()) {
      flowOf(zero)
    } else {
      val queuedDebtOwner = mutableMapOf<String, String>()
      val queuedPaymentDebt = mutableMapOf<String, String>()
      for (row in rows) {
        when (val payload = row.command.payload()) {
          is DebtCreate -> queuedDebtOwner[row.command.entityId] = payload.contactId
          is PaymentCreate -> queuedPaymentDebt[row.command.entityId] = payload.debtId
          else -> Unit
        }
      }
      val debtOwner: suspend (String) -> String? = {
        queuedDebtOwner[it] ?: scope.database.debtDao().contactId(it)
      }
      val paymentDebt: suspend (String) -> String? = {
        queuedPaymentDebt[it] ?: scope.database.paymentDao().debtId(it)
      }
      val people = pending.mapNotNull { personOf(it.command, debtOwner, paymentDebt) }.toSet()
      if (people.isEmpty()) {
        flowOf(zero)
      } else {
        combine(people.map { scope.personLedger.observe(it) }) { records ->
          var debts = Cents.ZERO
          var payments = Cents.ZERO
          var count = 0
          for (record in records) {
            val ledger = record?.ledger ?: continue
            val receivableIds =
                ledger.debts
                    .filter { it.direction == Direction.RECEIVABLE }
                    .mapTo(HashSet()) { it.id }
            for (debt in ledger.debts) {
              if (
                  debt.pending &&
                      debt.direction == Direction.RECEIVABLE &&
                      !debt.createdAt.isBefore(from) &&
                      debt.createdAt.isBefore(to)
              ) {
                debts += debt.amount
              }
            }
            for (payment in ledger.payments) {
              if (
                  payment.pending &&
                      !payment.reversed &&
                      payment.debtId in receivableIds &&
                      !payment.createdAt.isBefore(from) &&
                      payment.createdAt.isBefore(to)
              ) {
                payments += payment.amount
                count++
              }
            }
          }
          MonthAmounts(debts, payments, count)
        }
      }
    }
  }
}
