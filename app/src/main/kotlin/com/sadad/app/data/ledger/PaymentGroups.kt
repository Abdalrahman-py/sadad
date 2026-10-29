package com.sadad.app.data.ledger

import com.sadad.ledger.DebtView
import com.sadad.ledger.Direction
import com.sadad.ledger.PaymentMethod
import com.sadad.ledger.PaymentView
import java.time.Instant

private data class PaymentKey(
    val contactId: String,
    val at: Instant,
    val direction: Direction,
    val method: PaymentMethod,
    val note: String,
    val author: String,
)

/** Both payment flows stamp every allocation from one save with the same exact instant. */
internal fun paymentGroups(
    payments: List<PaymentView>,
    debts: Map<String, DebtView>,
): Collection<List<PaymentView>> =
    payments
        .groupBy {
          PaymentKey(
              it.contactId,
              it.createdAt,
              debts.getValue(it.debtId).direction,
              it.method,
              it.note,
              it.createdBy,
          )
        }
        .values
