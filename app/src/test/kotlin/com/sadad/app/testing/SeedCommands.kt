package com.sadad.app.testing

import com.sadad.app.data.ledger.OutboxState
import com.sadad.app.data.session.StoreScope
import com.sadad.ledger.Cents
import com.sadad.ledger.CommandType
import com.sadad.ledger.Commands
import com.sadad.ledger.Direction
import com.sadad.ledger.PaymentAllocation
import com.sadad.ledger.PaymentMethod
import java.time.Instant

/**
 * Seeds by queuing, through the outbox, exactly the commands the deleted `LedgerRepository` write
 * helpers (`addDebt`, `payDebt`, `payContact`) queued — with the same `dependsOn` rule for a row
 * the server has not confirmed yet. Every test that seeded with those helpers calls these; the
 * assertions did not change (ticket 167).
 */

/** The queued creating operation of [entityId], when their receipt has not been folded yet. */
private suspend fun StoreScope.pendingCreateOf(entityId: String, type: CommandType): String? =
    outbox
        .snapshot()
        .firstOrNull {
          it.entityId == entityId &&
              it.type == type &&
              (it.state == OutboxState.QUEUED || it.state == OutboxState.ACCEPTED)
        }
        ?.operationId

/** Queues the debt the deleted `addDebt` queued: it waits for its queued contact. */
suspend fun StoreScope.seedDebt(
    contactId: String,
    direction: Direction = Direction.RECEIVABLE,
    amount: Cents,
    note: String? = null,
    dueDate: String? = null,
): String {
  val command =
      Commands.debtCreate(
          checkNotNull(origin()),
          Instant.now(),
          contactId,
          direction,
          amount,
          note,
          dueDate,
          dependsOn = listOfNotNull(pendingCreateOf(contactId, CommandType.CONTACT_CREATE)),
      )
  outbox.append(listOf(command))
  onQueued()
  return command.entityId
}

/** Queues the payment the deleted `payDebt` queued: it waits for its queued debt. */
suspend fun StoreScope.seedPayment(
    debtId: String,
    amount: Cents,
    method: PaymentMethod = PaymentMethod.CASH,
    note: String? = null,
): String {
  val command =
      Commands.paymentCreate(
          checkNotNull(origin()),
          Instant.now(),
          debtId,
          amount,
          method,
          note,
          dependsOn = listOfNotNull(pendingCreateOf(debtId, CommandType.DEBT_CREATE)),
      )
  outbox.append(listOf(command))
  onQueued()
  return command.entityId
}

/**
 * Queues what the deleted `payContact` queued: the payment split over the person's open Debts,
 * oldest first, each part waiting for its queued debt.
 */
suspend fun StoreScope.seedContactPayment(
    contactId: String,
    amount: Cents,
    method: PaymentMethod = PaymentMethod.CASH,
    note: String? = null,
): List<String> {
  val view = checkNotNull(personLedger.records(contactId)).ledger
  val contact = checkNotNull(view.contact(contactId))
  val origin = checkNotNull(origin())
  // One instant for every part: the compound payment groups by the exact save time.
  val at = Instant.now()
  val commands =
      PaymentAllocation.allocate(view, contactId, amount, contact.kind.debtDirection).map { part ->
        Commands.paymentCreate(
            origin,
            at,
            part.debtId,
            part.amount,
            method,
            note,
            dependsOn = if (part.debtPending) listOf(part.debtOperationId) else emptyList(),
        )
      }
  outbox.append(commands)
  onQueued()
  return commands.map { it.entityId }
}
