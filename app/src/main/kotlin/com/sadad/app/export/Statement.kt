package com.sadad.app.export

import com.sadad.app.data.ledger.paymentGroups
import com.sadad.ledger.Cents
import com.sadad.ledger.ContactView
import com.sadad.ledger.DebtOrigin
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerView
import java.time.Instant

enum class MovementKind {
  DEBT,
  PAYMENT,
}

data class StatementRow(
    val id: String,
    val date: Instant,
    val kind: MovementKind,
    val debt: Cents,
    val payment: Cents,
    val balance: Cents,
    val dueDate: String?,
    val note: String,
    val createdBy: String,
)

data class ContactStatement(
    val contact: ContactView,
    val opening: Cents,
    val rows: List<StatementRow>,
    val totalDebt: Cents,
    val totalPaid: Cents,
    val closing: Cents,
)

object Statement {
  /**
   * A half-open period: [fromInclusive] and [toExclusive]; null means unbounded. The same
   * convention as `PeriodBounds`, so no caller subtracts a nanosecond to fit an inclusive end. Only
   * visible receivable movements count.
   */
  fun build(
      view: LedgerView,
      contactId: String?,
      fromInclusive: Instant?,
      toExclusive: Instant?,
  ): List<ContactStatement> {
    require(fromInclusive == null || toExclusive == null || fromInclusive <= toExclusive) {
      "from must not be after to"
    }
    val contacts = view.contacts.filter { contactId == null || it.id == contactId }
    val selected = contacts.map { it.id }.toHashSet()
    val debts =
        view.debts.filter { it.contactId in selected && it.direction == Direction.RECEIVABLE }
    val debtIds = debts.map { it.id }.toHashSet()
    // A credit Debt is the second half of the payment that left it: one row, at that one instant.
    val credits =
        view.debts
            .filter { it.origin == DebtOrigin.OVERPAYMENT && it.amount > Cents.ZERO }
            .associateBy { it.contactId to it.createdAt }
    val seen = view.payments.filter { !it.reversed && it.debtId in debtIds }
    val groups = paymentGroups(seen, debts.associateBy { it.id })
    val movements = HashMap<String, MutableList<StatementRow>>()
    for (debt in debts) {
      movements.getOrPut(debt.contactId) { mutableListOf() } +=
          StatementRow(
              debt.id,
              debt.createdAt,
              MovementKind.DEBT,
              debt.amount,
              Cents.ZERO,
              Cents.ZERO,
              debt.dueDate,
              debt.note,
              debt.createdBy,
          )
    }
    for (parts in groups) {
      val payment = parts.first()
      val key = payment.contactId to payment.createdAt
      val credit = credits[key]?.takeIf { it.createdBy == payment.createdBy }
      val settled = parts.fold(Cents.ZERO) { total, part -> total + part.amount }
      movements.getOrPut(payment.contactId) { mutableListOf() } +=
          StatementRow(
              payment.id,
              payment.createdAt,
              MovementKind.PAYMENT,
              Cents.ZERO,
              settled + (credit?.amount ?: Cents.ZERO),
              Cents.ZERO,
              null,
              payment.note,
              payment.createdBy,
          )
    }
    return contacts.map { contact ->
      val sorted = movements[contact.id].orEmpty().sortedWith(compareBy({ it.date }, { it.id }))
      var opening = Cents.ZERO
      var totalDebt = Cents.ZERO
      var totalPaid = Cents.ZERO
      val rows = mutableListOf<StatementRow>()
      var balance = Cents.ZERO
      for (row in sorted) {
        if (fromInclusive != null && row.date < fromInclusive) {
          opening += row.debt - row.payment
          balance = opening
        } else if (toExclusive == null || row.date < toExclusive) {
          balance += row.debt - row.payment
          totalDebt += row.debt
          totalPaid += row.payment
          rows += row.copy(balance = balance)
        }
      }
      ContactStatement(contact, opening, rows, totalDebt, totalPaid, balance)
    }
  }
}
