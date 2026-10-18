package com.sadad.app.export

import com.sadad.ledger.Cents
import com.sadad.ledger.ContactView
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerView
import java.time.Instant

enum class MovementKind { DEBT, PAYMENT }

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
  /** Inclusive period endpoints; null means unbounded. Only visible receivable movements count. */
  fun build(view: LedgerView, contactId: String?, from: Instant?, to: Instant?): List<ContactStatement> {
    require(from == null || to == null || from <= to) { "from must not be after to" }
    val contacts = view.contacts.filter { contactId == null || it.id == contactId }
    val selected = contacts.map { it.id }.toHashSet()
    val debts = view.debts.filter { it.contactId in selected && it.direction == Direction.RECEIVABLE }
    val debtIds = debts.map { it.id }.toHashSet()
    val movements = HashMap<String, MutableList<StatementRow>>()
    for (debt in debts) {
      movements.getOrPut(debt.contactId) { mutableListOf() } +=
          StatementRow(debt.id, debt.createdAt, MovementKind.DEBT, debt.amount, Cents.ZERO, Cents.ZERO, debt.dueDate, debt.note, debt.createdBy)
    }
    for (payment in view.payments) {
      if (payment.reversed || payment.debtId !in debtIds) continue
      movements.getOrPut(payment.contactId) { mutableListOf() } +=
          StatementRow(payment.id, payment.createdAt, MovementKind.PAYMENT, Cents.ZERO, payment.amount, Cents.ZERO, null, payment.note, payment.createdBy)
    }
    return contacts.map { contact ->
      val sorted = movements[contact.id].orEmpty().sortedWith(compareBy({ it.date }, { it.id }))
      var opening = Cents.ZERO
      var totalDebt = Cents.ZERO
      var totalPaid = Cents.ZERO
      val rows = mutableListOf<StatementRow>()
      var balance = Cents.ZERO
      for (row in sorted) {
        if (from != null && row.date < from) {
          opening += row.debt - row.payment
          balance = opening
        } else if (to == null || row.date <= to) {
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
