package com.sadad.app.data.db

import androidx.room.TypeConverter
import com.sadad.ledger.ContactKind
import com.sadad.ledger.DebtOrigin
import com.sadad.ledger.Direction
import com.sadad.ledger.PaymentMethod
import com.sadad.ledger.ReceiptStatus
import java.time.Instant

/**
 * Room stores the wire format: `java.time` and the Ledger enums have no column type of their own,
 * and the enums are written as the names `Vocabulary.kt` puts on the wire (`receivable`, `cash`,
 * …).
 */
class Converters {
  @TypeConverter fun instantToEpochMillis(value: Instant): Long = value.toEpochMilli()

  @TypeConverter fun epochMillisToInstant(value: Long): Instant = Instant.ofEpochMilli(value)

  @TypeConverter
  fun directionToText(value: Direction): String =
      Direction.serializer().descriptor.getElementName(value.ordinal)

  @TypeConverter
  fun textToDirection(value: String): Direction =
      Direction.entries.first { directionToText(it) == value }

  @TypeConverter
  fun contactKindToText(value: ContactKind): String =
      ContactKind.serializer().descriptor.getElementName(value.ordinal)

  @TypeConverter
  fun textToContactKind(value: String): ContactKind =
      ContactKind.entries.first { contactKindToText(it) == value }

  @TypeConverter
  fun paymentMethodToText(value: PaymentMethod): String =
      PaymentMethod.serializer().descriptor.getElementName(value.ordinal)

  @TypeConverter
  fun textToPaymentMethod(value: String): PaymentMethod =
      PaymentMethod.entries.first { paymentMethodToText(it) == value }

  @TypeConverter
  fun debtOriginToText(value: DebtOrigin): String =
      DebtOrigin.serializer().descriptor.getElementName(value.ordinal)

  @TypeConverter
  fun textToDebtOrigin(value: String): DebtOrigin =
      DebtOrigin.entries.first { debtOriginToText(it) == value }

  @TypeConverter
  fun receiptStatusToText(value: ReceiptStatus): String =
      ReceiptStatus.serializer().descriptor.getElementName(value.ordinal)

  @TypeConverter
  fun textToReceiptStatus(value: String): ReceiptStatus =
      ReceiptStatus.entries.first { receiptStatusToText(it) == value }
}
