package com.sadad.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.AppGraph
import com.sadad.app.export.MovementKind
import com.sadad.ledger.Cents
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerView
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** One row of the recent movements: enough to show it and open its Contact. */
data class HomeMovement(
    val contactId: String,
    val contactName: String,
    val amount: Cents,
    val kind: MovementKind,
    val pending: Boolean,
    val createdAt: Instant,
)

data class HomeUiState(
    val storeName: String = "",
    val month: YearMonth,
    val totalReceivable: Cents = Cents.ZERO,
    val monthDebts: Cents = Cents.ZERO,
    val monthPayments: Cents = Cents.ZERO,
    val debtsRatio: Float = 0f,
    val paymentsRatio: Float = 0f,
    val recent: List<HomeMovement> = emptyList(),
)

/**
 * Projects the one [LedgerView] onto the home screen: what customers owe, this month's receivable
 * Debts and non-reversed Payments, and the newest 50 visible movements. Month boundaries use
 * [zone]; money stays [Cents] until the ratios become drawing values.
 */
fun homeUiState(
    view: LedgerView,
    month: YearMonth,
    zone: ZoneId,
    storeName: String = "",
): HomeUiState {
  val start = month.atDay(1).atStartOfDay(zone).toInstant()
  val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
  fun inMonth(at: Instant) = !at.isBefore(start) && at.isBefore(end)

  val receivable = view.debts.filter { it.direction == Direction.RECEIVABLE }
  val receivableIds = receivable.mapTo(HashSet()) { it.id }
  val payments = view.payments.filter { !it.reversed }

  var monthDebts = Cents.ZERO
  for (debt in receivable) if (inMonth(debt.createdAt)) monthDebts += debt.amount
  var monthPayments = Cents.ZERO
  for (payment in payments) {
    if (payment.debtId in receivableIds && inMonth(payment.createdAt))
        monthPayments += payment.amount
  }
  val total = monthDebts + monthPayments

  val names = view.contacts.associate { it.id to it.name }
  val recent = buildList {
    for (debt in view.debts) {
      add(
          HomeMovement(
              debt.contactId,
              names[debt.contactId].orEmpty(),
              debt.amount,
              MovementKind.DEBT,
              debt.pending,
              debt.createdAt,
          )
      )
    }
    for (payment in payments) {
      add(
          HomeMovement(
              payment.contactId,
              names[payment.contactId].orEmpty(),
              payment.amount,
              MovementKind.PAYMENT,
              payment.pending,
              payment.createdAt,
          )
      )
    }
  }
      .sortedByDescending { it.createdAt }
      .take(50)

  return HomeUiState(
      storeName = storeName,
      month = month,
      totalReceivable = view.receivable,
      monthDebts = monthDebts,
      monthPayments = monthPayments,
      debtsRatio =
          if (total.value == 0L) 0f else monthDebts.value.toFloat() / total.value.toFloat(),
      paymentsRatio =
          if (total.value == 0L) 0f else monthPayments.value.toFloat() / total.value.toFloat(),
      recent = recent,
  )
}

/** Follows the open store's [LedgerView] and the account's local store name. */
class HomeViewModel(graph: AppGraph, private val clock: Clock = Clock.systemDefaultZone()) :
    ViewModel() {
  private val views =
      graph.current.flatMapLatest { scope ->
        val storeId = scope?.account?.storeUserId
        (scope?.repository?.view ?: flowOf(null)).map { view -> storeId to view }
      }

  val state: StateFlow<HomeUiState> =
      combine(views, graph.accounts.accounts) { (storeId, view), accounts ->
            val storeName = accounts.firstOrNull { it.storeUserId == storeId }?.storeName.orEmpty()
            val month = YearMonth.now(clock)
            if (view == null) HomeUiState(storeName = storeName, month = month)
            else homeUiState(view, month, clock.zone, storeName)
          }
          .flowOn(Dispatchers.Default)
          .stateIn(
              viewModelScope,
              SharingStarted.Eagerly,
              HomeUiState(month = YearMonth.now(clock)),
          )
}
