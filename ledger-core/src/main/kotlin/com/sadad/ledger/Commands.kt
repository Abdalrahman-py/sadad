package com.sadad.ledger

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.jsonObject

/**
 * Builders for every command type. Text is trimmed and blank optional text dropped. They throw
 * [IllegalArgumentException] for what the UI must catch before queueing: an amount outside
 * 1..[Cents.MAX_WIRE], a contact name that is blank or over 120 characters, a reason that is blank
 * or over 2000 characters, and an id or `dependsOn` entry that is not a lowercase canonical UUID.
 * Everything else (phone format, duplicates, versions) the server judges.
 */
object Commands {
  private const val MAX_NAME = 120
  private const val MAX_REASON = 2000

  fun contactCreate(
      origin: Origin,
      at: Instant,
      name: String,
      phone: String? = null,
      category: String? = null,
      note: String? = null,
      kind: ContactKind = ContactKind.CUSTOMER,
      entityId: String = newId(),
      operationId: String = newId(),
      dependsOn: List<String> = emptyList(),
  ): LedgerCommand =
      command(
          origin,
          at,
          CommandType.CONTACT_CREATE,
          entityId,
          operationId,
          dependsOn,
          ContactCreate.serializer(),
          ContactCreate(name(name), kind, optional(phone), optional(category), optional(note)),
      )

  fun contactUpdate(
      origin: Origin,
      at: Instant,
      contactId: String,
      expectedVersion: Long,
      name: String,
      phone: String? = null,
      category: String? = null,
      note: String? = null,
      kind: ContactKind = ContactKind.CUSTOMER,
      entityId: String = newId(),
      operationId: String = newId(),
      dependsOn: List<String> = emptyList(),
  ): LedgerCommand =
      command(
          origin,
          at,
          CommandType.CONTACT_UPDATE,
          entityId,
          operationId,
          dependsOn,
          ContactUpdate.serializer(),
          ContactUpdate(
              uuid("contactId", contactId),
              name(name),
              kind,
              optional(phone),
              optional(category),
              optional(note),
          ),
          expectedVersion = expectedVersion,
      )

  fun contactArchive(
      origin: Origin,
      at: Instant,
      contactId: String,
      expectedVersion: Long,
      reason: String,
      entityId: String = newId(),
      operationId: String = newId(),
      dependsOn: List<String> = emptyList(),
  ): LedgerCommand =
      command(
          origin,
          at,
          CommandType.CONTACT_ARCHIVE,
          entityId,
          operationId,
          dependsOn,
          ContactArchive.serializer(),
          ContactArchive(uuid("contactId", contactId), reason(reason)),
          expectedVersion = expectedVersion,
      )

  fun contactRestore(
      origin: Origin,
      at: Instant,
      contactId: String,
      expectedVersion: Long,
      reason: String,
      entityId: String = newId(),
      operationId: String = newId(),
      dependsOn: List<String> = emptyList(),
  ): LedgerCommand =
      command(
          origin,
          at,
          CommandType.CONTACT_RESTORE,
          entityId,
          operationId,
          dependsOn,
          ContactRestore.serializer(),
          ContactRestore(uuid("contactId", contactId), reason(reason)),
          expectedVersion = expectedVersion,
      )

  fun debtCreate(
      origin: Origin,
      at: Instant,
      contactId: String,
      direction: Direction,
      amount: Cents,
      note: String? = null,
      /** `yyyy-MM-dd`. */
      dueDate: String? = null,
      debtOrigin: DebtOrigin? = null,
      entityId: String = newId(),
      operationId: String = newId(),
      dependsOn: List<String> = emptyList(),
  ): LedgerCommand =
      command(
          origin,
          at,
          CommandType.DEBT_CREATE,
          entityId,
          operationId,
          dependsOn,
          DebtCreate.serializer(),
          DebtCreate(
              uuid("contactId", contactId),
              direction,
              amount(amount),
              optional(note),
              optional(dueDate),
              debtOrigin,
          ),
      )

  fun debtCorrect(
      origin: Origin,
      at: Instant,
      debtId: String,
      expectedVersion: Long,
      amount: Cents,
      reason: String,
      entityId: String = newId(),
      operationId: String = newId(),
      dependsOn: List<String> = emptyList(),
  ): LedgerCommand =
      command(
          origin,
          at,
          CommandType.DEBT_CORRECT,
          entityId,
          operationId,
          dependsOn,
          DebtCorrect.serializer(),
          DebtCorrect(uuid("debtId", debtId), correctionAmount(amount), reason(reason)),
          expectedVersion = expectedVersion,
      )

  fun debtOffset(
      origin: Origin,
      at: Instant,
      receivableDebtId: String,
      payableDebtId: String,
      amount: Cents,
      entityId: String = newId(),
      operationId: String = newId(),
      dependsOn: List<String> = emptyList(),
  ): LedgerCommand =
      command(
          origin,
          at,
          CommandType.DEBT_OFFSET,
          entityId,
          operationId,
          dependsOn,
          DebtOffset.serializer(),
          DebtOffset(
              uuid("receivableDebtId", receivableDebtId),
              uuid("payableDebtId", payableDebtId),
              amount(amount),
          ),
      )

  fun paymentCreate(
      origin: Origin,
      at: Instant,
      debtId: String,
      amount: Cents,
      method: PaymentMethod,
      note: String? = null,
      entityId: String = newId(),
      operationId: String = newId(),
      dependsOn: List<String> = emptyList(),
  ): LedgerCommand =
      command(
          origin,
          at,
          CommandType.PAYMENT_CREATE,
          entityId,
          operationId,
          dependsOn,
          PaymentCreate.serializer(),
          PaymentCreate(uuid("debtId", debtId), amount(amount), method, optional(note)),
      )

  fun paymentReverse(
      origin: Origin,
      at: Instant,
      paymentId: String,
      reason: String,
      entityId: String = newId(),
      operationId: String = newId(),
      dependsOn: List<String> = emptyList(),
  ): LedgerCommand =
      command(
          origin,
          at,
          CommandType.PAYMENT_REVERSE,
          entityId,
          operationId,
          dependsOn,
          PaymentReverse.serializer(),
          PaymentReverse(uuid("paymentId", paymentId), reason(reason)),
      )

  private fun newId(): String = UUID.randomUUID().toString()

  private fun <P : CommandPayload> command(
      origin: Origin,
      at: Instant,
      type: CommandType,
      entityId: String,
      operationId: String,
      dependsOn: List<String>,
      serializer: KSerializer<P>,
      payload: P,
      expectedVersion: Long? = null,
  ) =
      LedgerCommand(
          operationId = uuid("operationId", operationId),
          entityId = uuid("entityId", entityId),
          type = type,
          originInstallationId = origin.installationId,
          originGeneration = origin.generation,
          expectedEpoch = origin.epoch,
          // Instant.toString is ISO-8601 in UTC with Z, whatever the device's zone.
          createdAt = at.truncatedTo(ChronoUnit.MILLIS).toString(),
          dependsOn = dependsOn.map { uuid("dependsOn", it) },
          expectedVersion = expectedVersion,
          payload = LedgerJson.encodeToJsonElement(serializer, payload).jsonObject,
      )

  /**
   * The server casts every id to `uuid` outside its per-command handling, so one id it cannot read
   * fails the whole batch. Uppercase is refused here as well, stricter than the cast, so every id
   * the app queues is the canonical lowercase text Postgres stores and the ledger compares.
   */
  private fun uuid(name: String, value: String): String = value.also {
    require(it == runCatching { UUID.fromString(it) }.getOrNull()?.toString()) {
      "$name is not a UUID"
    }
  }

  private fun optional(text: String?): String? = text?.trim()?.ifEmpty { null }

  /** Postgres `length` counts characters, so count code points, not UTF-16 units. */
  private fun String.characters(): Int = codePointCount(0, length)

  private fun name(text: String): String =
      text.trim().also {
        require(it.characters() in 1..MAX_NAME) { "a contact name has 1 to $MAX_NAME characters" }
      }

  private fun reason(text: String): String =
      text.trim().also {
        require(it.characters() in 1..MAX_REASON) { "a reason has 1 to $MAX_REASON characters" }
      }

  private fun amount(cents: Cents): Cents = cents.also {
    require(it >= Cents(1) && it <= Cents.MAX_WIRE) {
      "an amount is 1 to ${Cents.MAX_WIRE.value} cents"
    }
  }

  /** A correction may zero an unused Debt (reversing credit); the server guards the rest. */
  private fun correctionAmount(cents: Cents): Cents = cents.also {
    require(it >= Cents.ZERO && it <= Cents.MAX_WIRE) {
      "a correction is 0 to ${Cents.MAX_WIRE.value} cents"
    }
  }
}
