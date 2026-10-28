package com.sadad.ledger

/** One part of a Contact payment; the caller builds one `payment.create` per part. */
data class Allocation(
    val debtId: String,
    val amount: Cents,
    /** The Debt's creating operation; add it to `dependsOn` when [debtPending]. */
    val debtOperationId: String,
    val debtPending: Boolean,
)

/** The payment parts for the open Debts, plus the excess that becomes the Contact's credit. */
data class CreditAllocation(
    val parts: List<Allocation>,
    val creditCents: Cents,
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
    return split(open, amount).first
  }

  /**
   * Like [allocate], but a customer's payment above what is open is accepted: the parts settle the
   * open Debts and [CreditAllocation.creditCents] is the excess, which the caller turns into a
   * payable `origin: overpayment` Debt. Suppliers stay capped, so [direction] must be receivable.
   */
  fun allocateWithCredit(
      view: LedgerView,
      contactId: String,
      amount: Cents,
      direction: Direction,
  ): CreditAllocation {
    require(direction == Direction.RECEIVABLE) { "only a customer's overpayment becomes credit" }
    require(amount > Cents.ZERO) { "a payment must be positive" }
    val (parts, left) = split(openDebts(view, contactId, direction), amount)
    return CreditAllocation(parts, left)
  }

  /**
   * How a new debt of [amount] is paid from the Contact's credit: one part per open `origin:
   * overpayment` payable Debt, oldest first, capped at what the credit covers. A manual payable is
   * never credit. The caller builds one `debt.offset` per part against the new Debt.
   */
  fun allocateCredit(view: LedgerView, contactId: String, amount: Cents): List<Allocation> {
    require(amount > Cents.ZERO) { "a debt must be positive" }
    val credit =
        view.debts
            .filter {
              it.contactId == contactId &&
                  it.direction == Direction.PAYABLE &&
                  it.origin == DebtOrigin.OVERPAYMENT &&
                  it.remaining > Cents.ZERO
            }
            .sortedBy { it.createdAt }
    return split(credit, amount).first
  }

  /** Oldest first, each open Debt taking what is left; the pair is the parts and the remainder. */
  private fun split(open: List<DebtView>, amount: Cents): Pair<List<Allocation>, Cents> {
    var left = amount
    val parts = mutableListOf<Allocation>()
    for (debt in open) {
      if (left == Cents.ZERO) break
      val part = minOf(debt.remaining, left)
      parts += Allocation(debt.id, part, debt.operationId, debt.pending)
      left -= part
    }
    return parts to left
  }
}
