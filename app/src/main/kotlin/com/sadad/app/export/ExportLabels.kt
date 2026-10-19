package com.sadad.app.export

import android.content.Context
import androidx.annotation.StringRes
import com.sadad.app.R

enum class ExportLabel(@StringRes val resourceId: Int) {
  TITLE(R.string.export_title),
  STORE(R.string.export_store),
  OWNER(R.string.export_owner),
  CONTACT(R.string.export_contact),
  PHONE(R.string.export_phone),
  CATEGORY(R.string.export_category),
  CONTACT_NOTE(R.string.export_contact_note),
  PERIOD(R.string.export_period),
  DATE(R.string.export_date),
  MOVEMENT(R.string.export_movement),
  DEBT_COLUMN(R.string.export_debt_column),
  PAYMENT_COLUMN(R.string.export_payment_column),
  BALANCE_COLUMN(R.string.export_balance_column),
  DUE_DATE(R.string.export_due_date),
  DESCRIPTION(R.string.export_description),
  OPENING(R.string.export_opening),
  OPENING_DESCRIPTION(R.string.export_opening_description),
  CLOSING_DESCRIPTION(R.string.export_closing_description),
  DEBT(R.string.export_debt),
  PAYMENT(R.string.export_payment),
  TOTAL(R.string.export_total),
  EMPTY(R.string.export_empty),
  CONTINUED(R.string.export_continued),
  REGISTERED_BY(R.string.export_registered_by),
  PAGE(R.string.export_page),
  SHARE(R.string.export_share),
}

/** Resource values are loaded once; the workbook writer needs no Android runtime. */
class ExportLabels(private val text: (ExportLabel) -> String) {
  operator fun get(key: ExportLabel): String = text(key)

  val columns: List<String>
    get() =
        listOf(
                ExportLabel.DATE,
                ExportLabel.MOVEMENT,
                ExportLabel.DEBT_COLUMN,
                ExportLabel.PAYMENT_COLUMN,
                ExportLabel.BALANCE_COLUMN,
                ExportLabel.DUE_DATE,
                ExportLabel.DESCRIPTION,
            )
            .map { get(it) }

  companion object {
    fun from(context: Context): ExportLabels {
      val values = ExportLabel.entries.associateWith { context.getString(it.resourceId) }
      return ExportLabels { values.getValue(it) }
    }
  }
}
