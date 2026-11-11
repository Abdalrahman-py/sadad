package com.sadad.app.feature.home

import com.sadad.ledger.Cents
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerView
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The whole-view derivation itself, the independent reference the Room-query tests (tickets 162,
 * 160) compare against. Ticket 168 deleted it from the app, because no screen derives from a ledger
 *      view anymore; here it only answers "what the same seed would say, derived in memory".
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
      for (debt in view.debtsOf(contact.id)) {
        if (debt.direction != Direction.RECEIVABLE || debt.remaining <= Cents.ZERO) continue
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
      .sortedWith(referenceFollowUpOrder)

  return HomeUiState(
      storeName = storeName,
      month = month,
      owedToStore = owed,
      storeOwes = owes,
      monthDebts = monthDebts,
      monthPayments = monthPayments,
      monthPaymentsCount = monthPaymentsCount,
      collectionPercent = referenceCollectionPercent(monthDebts, monthPayments),
      followUps = chasing.take(FOLLOW_UP_ROWS),
      followUpCount = chasing.size,
      empty = view.contacts.isEmpty() && view.debts.isEmpty() && view.payments.isEmpty(),
      offline = if (offline && queued > 0) HomeOffline(queued, leaseEndsAt) else null,
  )
}

private val referenceFollowUpOrder: Comparator<HomeFollowUp> =
    compareByDescending<HomeFollowUp> { it.lateDays ?: 0 }
        .thenByDescending { it.amount.value }
        .thenBy { it.name }

private fun referenceCollectionPercent(monthDebts: Cents, monthPayments: Cents): Int =
    if (monthDebts.value > 0L) {
          ((monthPayments.value * 100L + monthDebts.value / 2) / monthDebts.value).coerceAtMost(
              100L
          )
        } else {
          0L
        }
        .toInt()
