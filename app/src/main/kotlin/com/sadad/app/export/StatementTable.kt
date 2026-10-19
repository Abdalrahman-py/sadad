package com.sadad.app.export

import com.sadad.ledger.Cents
import java.math.BigDecimal
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun Cents.decimal(): String = BigDecimal.valueOf(value, 2).toPlainString()

internal data class TableRow(
    val cells: List<String>,
    val numeric: Set<Int> = emptySet(),
    val total: Boolean = false,
)

internal fun ContactStatement.tableRows(labels: ExportLabels): Sequence<TableRow> = sequence {
  yield(
      TableRow(
          listOf(
              "",
              labels[ExportLabel.OPENING],
              "",
              "",
              opening.decimal(),
              "",
              labels[ExportLabel.OPENING_DESCRIPTION],
          ),
          setOf(4),
      )
  )
  val date =
      DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault())
  for (row in rows) {
    val isDebt = row.kind == MovementKind.DEBT
    val note =
        listOf(
                row.note,
                row.createdBy
                    .takeIf { it.isNotBlank() }
                    ?.let { "${labels[ExportLabel.REGISTERED_BY]}: $it" }
                    .orEmpty(),
            )
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    yield(
        TableRow(
            listOf(
                date.format(row.date),
                labels[if (isDebt) ExportLabel.DEBT else ExportLabel.PAYMENT],
                if (isDebt) row.debt.decimal() else "",
                if (isDebt) "" else row.payment.decimal(),
                row.balance.decimal(),
                row.dueDate.orEmpty(),
                note,
            ),
            setOf(if (isDebt) 2 else 3, 4),
        )
    )
  }
  if (rows.isEmpty()) yield(TableRow(listOf("", "", "", "", "", "", labels[ExportLabel.EMPTY])))
  yield(
      TableRow(
          listOf(
              "",
              labels[ExportLabel.TOTAL],
              totalDebt.decimal(),
              totalPaid.decimal(),
              closing.decimal(),
              "",
              labels[ExportLabel.CLOSING_DESCRIPTION],
          ),
          setOf(2, 3, 4),
          total = true,
      )
  )
}
