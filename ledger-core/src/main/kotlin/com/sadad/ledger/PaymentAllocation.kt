package com.sadad.ledger

/** One part of a Contact payment; the caller builds one `payment.create` per part. */
data class Allocation(
    val debtId: String,
    val amount: Cents,
    /** The Debt's creating operation; add it to `dependsOn` when [debtPending]. */
    val debtOperationId: String,
    val debtPending: Boolean,
)

object PaymentAllocation {
  /**
   * The Contact's visible Debts of [direction] that are still open, oldest `createdAt` first (ties
   * keep view order). There is no default direction: a customer's Debts are receivable and a
   * supplier's payable, and the caller passes [ContactKind.debtDirection].
   */
  fun openDebts(view: LedgerView, contactId: String, direction: Direction): List<DebtView> =
      view.debts
          .filter {
            it.contactId == contactId && it.direction == direction && it.remaining > Cents.ZERO
          }
          .sortedBy { it.createdAt }

  /**
   * What is still open on those Debts. A save guard and the allocation it protects must read the
   * same number, so both go through this function rather than filtering the view themselves.
   */
  fun openBalance(view: LedgerView, contactId: String, direction: Direction): Cents =
      openDebts(view, contactId, direction).fold(Cents.ZERO) { sum, debt -> sum + debt.remaining }

  /**
   * Splits [amount] across the Contact's open Debts of [direction], each taking what is left on it.
   * Queued Payments already count. Throws [IllegalArgumentException] when [amount] is not positive
   * or exceeds what is open, so an overpayment changes nothing.
   */
  fun allocate(
      view: LedgerView,
      contactId: String,
      amount: Cents,
      direction: Direction,
  ): List<Allocation> {
    require(amount > Cents.ZERO) { "a payment must be positive" }
    val open = openDebts(view, contactId, direction)
    val total = open.fold(Cents.ZERO) { sum, debt -> sum + debt.remaining }
    require(amount <= total) { "the payment exceeds what the contact owes" }

    var left = amount
    val parts = mutableListOf<Allocation>()
    for (debt in open) {
      if (left == Cents.ZERO) break
      val part = minOf(debt.remaining, left)
      parts += Allocation(debt.id, part, debt.operationId, debt.pending)
      left -= part
    }
    return parts
  }
}
