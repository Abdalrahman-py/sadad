package com.sadad.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.AppGraph
import com.sadad.app.data.ledger.OutboxState
import com.sadad.app.data.sync.SyncStatus
import com.sadad.ledger.Cents
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerView
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** One person on «تحتاج متابعة»: what they owe, and how late — `null` means due today. */
data class HomeFollowUp(
    val contactId: String,
    val name: String,
    val lateDays: Int?,
    val amount: Cents,
)

/** The offline banner: how many entries wait, and how long the store may keep recording. */
data class HomeOffline(val queued: Int, val leaseEndsAt: Instant?)

data class HomeUiState(
    val storeName: String = "",
    val month: YearMonth,
    /** «لك عند الناس»: the sum of every positive net. */
    val owedToStore: Cents = Cents.ZERO,
    /** «عليك للناس»: the sum of every negative net, as a positive amount. */
    val storeOwes: Cents = Cents.ZERO,
    val monthDebts: Cents = Cents.ZERO,
    val monthPayments: Cents = Cents.ZERO,
    val monthPaymentsCount: Int = 0,
    /** Collection ÷ month debts, capped at 100. */
    val collectionPercent: Int = 0,
    val followUps: List<HomeFollowUp> = emptyList(),
    /** Everyone who needs chasing, not only the rows drawn. */
    val followUpCount: Int = 0,
    val empty: Boolean = false,
    val offline: HomeOffline? = null,
)

/**
 * Answers the two questions Home exists for. «لك عند الناس» is the sum of the positive
 * [LedgerView.net] over every person and «عليك للناس» the sum of the negative ones, so a supplier's
 * balance never inflates what customers owe. The month counts customers' Debts and the Payments
 * against them only, the collection share is capped, and follow-up lists people who owe and have a
 * receivable Debt overdue or due on [today], most days late first.
 */
fun homeUiState(
    view: LedgerView,
    month: YearMonth,
    zone: ZoneId,
    storeName: String = "",
    today: LocalDate,
    queued: Int = 0,
    offline: Boolean = false,
    leaseEndsAt: Instant? = null,
): HomeUiState {
  val start = month.atDay(1).atStartOfDay(zone).toInstant()
  val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
  fun inMonth(at: Instant) = !at.isBefore(start) && at.isBefore(end)

  var owed = Cents.ZERO
  var owes = Cents.ZERO
  val nets =
      view.contacts.associate { contact ->
        val net = view.net(contact.id)
        if (net > Cents.ZERO) owed += net else if (net < Cents.ZERO) owes += Cents(-net.value)
        contact.id to net
      }

  val receivable = view.debts.filter { it.direction == Direction.RECEIVABLE }
  val receivableIds = receivable.mapTo(HashSet()) { it.id }
  var monthDebts = Cents.ZERO
  for (debt in receivable) if (inMonth(debt.createdAt)) monthDebts += debt.amount
  var monthPayments = Cents.ZERO
  var monthPaymentsCount = 0
  for (payment in view.payments) {
    if (payment.reversed || payment.debtId !in receivableIds || !inMonth(payment.createdAt))
        continue
    monthPayments += payment.amount
    monthPaymentsCount++
  }

  val chasing = buildList {
    for (contact in view.contacts) {
      val net = nets[contact.id] ?: Cents.ZERO
      if (net <= Cents.ZERO) continue
      var late: Int? = null
      var dueToday = false
      for (debt in receivable) {
        if (debt.contactId != contact.id || debt.remaining <= Cents.ZERO) continue
        val due = debt.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (due == null) continue
        val days = ChronoUnit.DAYS.between(due, today).toInt()
        when {
          days > 0 -> late = maxOf(late ?: 0, days)
          days == 0 -> dueToday = true
        }
      }
      when {
        late != null -> add(HomeFollowUp(contact.id, contact.name, late, net))
        dueToday -> add(HomeFollowUp(contact.id, contact.name, null, net))
      }
    }
  }
      .sortedWith(
          compareByDescending<HomeFollowUp> { it.lateDays ?: 0 }
              .thenByDescending { it.amount.value }
              .thenBy { it.name }
      )

  val share =
      if (monthDebts.value > 0L) {
        ((monthPayments.value * 100L + monthDebts.value / 2) / monthDebts.value).coerceAtMost(100L)
      } else {
        0L
      }

  return HomeUiState(
      storeName = storeName,
      month = month,
      owedToStore = owed,
      storeOwes = owes,
      monthDebts = monthDebts,
      monthPayments = monthPayments,
      monthPaymentsCount = monthPaymentsCount,
      collectionPercent = share.toInt(),
      followUps = chasing.take(FOLLOW_UP_ROWS),
      followUpCount = chasing.size,
      empty = view.contacts.isEmpty() && view.debts.isEmpty() && view.payments.isEmpty(),
      offline = if (offline && queued > 0) HomeOffline(queued, leaseEndsAt) else null,
  )
}

/** At most this many rows on «تحتاج متابعة»; the rest are behind «عرض الكل». */
const val FOLLOW_UP_ROWS: Int = 5

/** Follows the open store: its [LedgerView], the queue, the connection, and the store's name. */
class HomeViewModel(graph: AppGraph, private val clock: Clock? = null) : ViewModel() {
  private data class Period(val month: YearMonth, val zone: ZoneId, val today: LocalDate)

  private fun clockNow(): Clock = clock ?: Clock.systemDefaultZone()

  private fun periodNow(): Period {
    val current = clockNow()
    val now = current.instant().atZone(current.zone)
    return Period(YearMonth.from(now), now.zone, now.toLocalDate())
  }

  private val period = MutableStateFlow(periodNow())

  /**
   * The screen cancels this wait when hidden and restarts it on return. It wakes at midnight, not
   * only at the month boundary, because «متأخر 3 أيام» gains a day there.
   */
  suspend fun followMonth() {
    while (true) {
      period.value = periodNow()
      val current = clockNow()
      val now = current.instant().atZone(current.zone)
      val midnight = now.toLocalDate().plusDays(1).atStartOfDay(current.zone)
      delay(Duration.between(now, midnight).toMillis().coerceAtLeast(1_000))
    }
  }

  private data class Inputs(
      val view: LedgerView?,
      val storeName: String,
      val period: Period,
      val queued: Int,
      val offline: Boolean,
      val leaseEndsAt: Instant?,
  )

  private val inputs: Flow<Inputs> =
      combine(graph.current, graph.accounts.accounts, period) { scope, accounts, period ->
            Triple(scope, accounts, period)
          }
          .flatMapLatest { (scope, accounts, period) ->
            val name =
                accounts
                    .firstOrNull { it.storeUserId == scope?.account?.storeUserId }
                    ?.storeName
                    .orEmpty()
            if (scope == null) {
              flowOf(Inputs(null, name, period, 0, false, null))
            } else {
              combine(
                  scope.repository.view,
                  scope.outbox.rows,
                  graph.syncStatus(scope.account.storeUserId),
              ) { view, rows, status ->
                Inputs(
                    view = view,
                    storeName = name,
                    period = period,
                    queued = rows.count { it.state == OutboxState.QUEUED },
                    offline = status == SyncStatus.Offline || !scope.isOnline(),
                    leaseEndsAt = graph.offlineUntil(scope.account)?.let(Instant::ofEpochMilli),
                )
              }
            }
          }

  val state: StateFlow<HomeUiState> =
      inputs
          .map { inputs ->
            val view = inputs.view
            if (view == null) {
              HomeUiState(storeName = inputs.storeName, month = inputs.period.month)
            } else {
              homeUiState(
                  view = view,
                  month = inputs.period.month,
                  zone = inputs.period.zone,
                  storeName = inputs.storeName,
                  today = inputs.period.today,
                  queued = inputs.queued,
                  offline = inputs.offline,
                  leaseEndsAt = inputs.leaseEndsAt,
              )
            }
          }
          .flowOn(Dispatchers.Default)
          .stateIn(
              viewModelScope,
              SharingStarted.Eagerly,
              HomeUiState(month = period.value.month),
          )
}
