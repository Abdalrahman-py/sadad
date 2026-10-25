package com.sadad.app.feature.contacts

import com.sadad.app.data.db.Events
import com.sadad.app.testing.TestGraph
import com.sadad.ledger.Cents
import com.sadad.ledger.Commands
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerCommand
import java.util.UUID

/** Ledger commands and pages the contacts and people tests seed a store with. */
internal suspend fun TestGraph.seedCommands(commands: List<LedgerCommand>) {
  seed { applyPage(pageOf(commands)) }
}

internal fun pageOf(commands: List<LedgerCommand>) =
    Events.page(commands.mapIndexed { index, command -> Events.accepted(command, index + 1L) })

internal fun contactId(index: Int): String =
    UUID.nameUUIDFromBytes("contact-$index".toByteArray()).toString()

internal fun contact(
    name: String,
    id: String = UUID.nameUUIDFromBytes(name.toByteArray()).toString(),
    phone: String? = null,
    minute: Int = 0,
    operationId: String = UUID.randomUUID().toString(),
): LedgerCommand =
    Commands.contactCreate(
        Events.origin,
        Events.at(minute),
        name = name,
        phone = phone,
        entityId = id,
        operationId = operationId,
    )

internal fun debt(
    contactId: String,
    amount: Long,
    direction: Direction = Direction.RECEIVABLE,
    id: String = UUID.randomUUID().toString(),
    minute: Int = 0,
    operationId: String = UUID.randomUUID().toString(),
): LedgerCommand =
    Commands.debtCreate(
        Events.origin,
        Events.at(minute),
        contactId = contactId,
        direction = direction,
        amount = Cents(amount),
        entityId = id,
        operationId = operationId,
    )

internal fun payment(
    debtId: String,
    amount: Long,
    id: String = UUID.randomUUID().toString(),
    minute: Int = 0,
    operationId: String = UUID.randomUUID().toString(),
): LedgerCommand =
    Commands.paymentCreate(
        Events.origin,
        Events.at(minute),
        debtId = debtId,
        amount = Cents(amount),
        method = com.sadad.ledger.PaymentMethod.CASH,
        entityId = id,
        operationId = operationId,
    )
