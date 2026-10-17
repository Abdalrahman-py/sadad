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
   * Splits [amount] across the Contact's visible receivable Debts that are still open, oldest
   * `createdAt` first (ties keep view order), each taking what is left on it. Queued Payments
   * already count. Throws [IllegalArgumentException] when [amount] is not positive or exceeds what
   * is open, so an overpayment changes nothing.
   */
  fun allocate(view: LedgerView, contactId: String, amount: Cents): List<Allocation> {
    require(amount > Cents.ZERO) { "a payment must be positive" }
    val open =
        view.debts
            .filter {
              it.contactId == contactId &&
                  it.direction == Direction.RECEIVABLE &&
                  it.remaining > Cents.ZERO
            }
            .sortedBy { it.createdAt }
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
