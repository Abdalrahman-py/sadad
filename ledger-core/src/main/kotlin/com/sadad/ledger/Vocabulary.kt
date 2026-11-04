package com.sadad.ledger

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The command types `24_apply_one.sql` accepts, with the payload each one carries. */
@Serializable
enum class CommandType(
    /** May be queued under an offline lease (`LEASE_ALLOWED` in the api's mobile.ts). */
    val offline: Boolean,
    internal val payloadSerializer: KSerializer<out CommandPayload>,
) {
  @SerialName("contact.create") CONTACT_CREATE(true, ContactCreate.serializer()),
  @SerialName("contact.update") CONTACT_UPDATE(false, ContactUpdate.serializer()),
  @SerialName("contact.archive") CONTACT_ARCHIVE(false, ContactArchive.serializer()),
  @SerialName("contact.restore") CONTACT_RESTORE(false, ContactRestore.serializer()),
  @SerialName("contact.delete") CONTACT_DELETE(false, ContactDelete.serializer()),
  @SerialName("debt.create") DEBT_CREATE(true, DebtCreate.serializer()),
  @SerialName("debt.offset") DEBT_OFFSET(true, DebtOffset.serializer()),
  @SerialName("debt.correct") DEBT_CORRECT(false, DebtCorrect.serializer()),
  @SerialName("payment.create") PAYMENT_CREATE(true, PaymentCreate.serializer()),
  @SerialName("payment.reverse") PAYMENT_REVERSE(false, PaymentReverse.serializer());

  /** The name on the wire, e.g. `contact.create`. */
  val wireName: String
    get() = serializer().descriptor.getElementName(ordinal)
}

/** Where a Debt came from: recorded by hand, or the excess of a customer's payment (credit). */
@Serializable
enum class DebtOrigin {
  @SerialName("manual") MANUAL,
  @SerialName("overpayment") OVERPAYMENT,
}

@Serializable
enum class Direction {
  /** The Contact owes the store. */
  @SerialName("receivable") RECEIVABLE,

  /** The store owes the Contact. */
  @SerialName("payable") PAYABLE,
}

/** Whether a Contact is a customer the store collects from or a supplier the store pays. */
@Serializable
enum class ContactKind {
  @SerialName("customer") CUSTOMER,
  @SerialName("supplier") SUPPLIER;

  /** The one place the kind decides a Debt's direction. */
  val debtDirection: Direction
    get() = if (this == CUSTOMER) Direction.RECEIVABLE else Direction.PAYABLE
}

@Serializable
enum class PaymentMethod {
  @SerialName("cash") CASH,
  @SerialName("bank") BANK,
  @SerialName("wallet") WALLET,
  @SerialName("wallet_jawwal") WALLET_JAWWAL,
  @SerialName("wallet_palpay") WALLET_PALPAY,
}
