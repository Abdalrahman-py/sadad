package com.sadad.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sadad.ledger.Cents
import com.sadad.ledger.ChangeEvent
import com.sadad.ledger.ContactRow
import com.sadad.ledger.DebtRow
import com.sadad.ledger.Direction
import com.sadad.ledger.PaymentMethod
import com.sadad.ledger.PaymentRow
import com.sadad.ledger.ReceiptStatus
import java.time.Instant

/**
 * A confirmed Contact as a row. [createdSeq] is the sequence of the event that created it, so
 * [LedgerStore.load] can rebuild the event order [com.sadad.ledger.PaymentAllocation] needs.
 */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String,
    val category: String,
    val note: String,
    val archived: Boolean,
    val version: Long,
    val operationId: String,
    val createdAt: Instant,
    val originInstallationId: String,
    val createdSeq: Long,
)

fun ContactEntity.toRow(): ContactRow =
    ContactRow(
        id,
        name,
        phone,
        category,
        note,
        archived,
        version,
        operationId,
        createdAt,
        originInstallationId,
    )

fun ContactRow.toEntity(createdSeq: Long): ContactEntity =
    ContactEntity(
        id,
        name,
        phone,
        category,
        note,
        archived,
        version,
        operationId,
        createdAt,
        originInstallationId,
        createdSeq,
    )

@Entity(tableName = "debts", indices = [Index("contactId")])
data class DebtEntity(
    @PrimaryKey val id: String,
    val contactId: String,
    val direction: Direction,
    val amountCents: Long,
    val note: String,
    val dueDate: String?,
    val version: Long,
    val operationId: String,
    val createdAt: Instant,
    val originInstallationId: String,
    val createdSeq: Long,
)

fun DebtEntity.toRow(): DebtRow =
    DebtRow(
        id,
        contactId,
        direction,
        Cents(amountCents),
        note,
        dueDate,
        version,
        operationId,
        createdAt,
        originInstallationId,
    )

fun DebtRow.toEntity(createdSeq: Long): DebtEntity =
    DebtEntity(
        id,
        contactId,
        direction,
        amount.value,
        note,
        dueDate,
        version,
        operationId,
        createdAt,
        originInstallationId,
        createdSeq,
    )

@Entity(tableName = "payments", indices = [Index("debtId")])
data class PaymentEntity(
    @PrimaryKey val id: String,
    val debtId: String,
    val amountCents: Long,
    val method: PaymentMethod,
    val note: String,
    val reversed: Boolean,
    val operationId: String,
    val createdAt: Instant,
    val originInstallationId: String,
    val createdSeq: Long,
)

fun PaymentEntity.toRow(): PaymentRow =
    PaymentRow(
        id,
        debtId,
        Cents(amountCents),
        method,
        note,
        reversed,
        operationId,
        createdAt,
        originInstallationId,
    )

fun PaymentRow.toEntity(createdSeq: Long): PaymentEntity =
    PaymentEntity(
        id,
        debtId,
        amount.value,
        method,
        note,
        reversed,
        operationId,
        createdAt,
        originInstallationId,
        createdSeq,
    )

/** The single row (`id = 0`) that holds the store, its epoch and the last sequence folded. */
@Entity(tableName = "ledger_meta")
data class LedgerMetaEntity(
    @PrimaryKey val id: Int = 0,
    val storeId: Long?,
    val epoch: String?,
    val cursor: Long,
)

/** One applied operation, for the history screen and the rejected verdicts. */
@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey val sequence: Long,
    val operationId: String,
    val kind: String,
    val entityId: String,
    val installationId: String,
    val createdAt: Long,
    val status: ReceiptStatus,
    val code: String?,
    /** The command JSON text, kept raw: a rejected command may be one this client cannot read. */
    val command: String,
)

fun ChangeEvent.toEntity(): EventEntity =
    EventEntity(
        sequence,
        operationId,
        kind,
        entityId,
        installationId,
        createdAtInstant().toEpochMilli(),
        result.status,
        result.code,
        command.toString(),
    )

/** A queued command. Ticket 45 owns its rules; this ticket only gives it columns and a DAO. */
@Entity(tableName = "outbox", indices = [Index(value = ["operationId"], unique = true)])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val operationId: String,
    val entityId: String,
    val type: String,
    val state: String,
    val code: String?,
    val createdAt: Long,
    val recoveryPermit: String?,
    val payload: ByteArray,
)
