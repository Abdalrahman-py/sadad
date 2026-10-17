package com.sadad.ledger

import kotlinx.serialization.Serializable

/**
 * The `payload` of each command type, with exactly the fields `24_apply_one.sql` reads. Optional
 * text is `null` when blank, so it is left out of the JSON.
 */
sealed interface CommandPayload

@Serializable
data class ContactCreate(
    val name: String,
    val phone: String? = null,
    val category: String? = null,
    val note: String? = null,
) : CommandPayload

@Serializable
data class ContactUpdate(
    val contactId: String,
    val name: String,
    val phone: String? = null,
    val category: String? = null,
    val note: String? = null,
) : CommandPayload

@Serializable data class ContactArchive(val contactId: String, val reason: String) : CommandPayload

@Serializable data class ContactRestore(val contactId: String, val reason: String) : CommandPayload

@Serializable
data class DebtCreate(
    val contactId: String,
    val direction: Direction,
    val amountCents: Cents,
    val note: String? = null,
    /** `yyyy-MM-dd`. */
    val dueDate: String? = null,
) : CommandPayload

@Serializable
data class DebtCorrect(val debtId: String, val amountCents: Cents, val reason: String) :
    CommandPayload

@Serializable
data class PaymentCreate(
    val debtId: String,
    val amountCents: Cents,
    val method: PaymentMethod,
    val note: String? = null,
) : CommandPayload

@Serializable data class PaymentReverse(val paymentId: String, val reason: String) : CommandPayload
