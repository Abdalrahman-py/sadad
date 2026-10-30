package com.sadad.app.feature.history

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sadad.app.R
import com.sadad.app.ui.components.Chip
import com.sadad.app.ui.components.ChipRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The five period choices as the boards' pills, the custom range opening Material's date range
 * picker. A null value is «فترة مخصصة».
 */
@Composable
fun PeriodFilter(
    period: Period,
    onPreset: (PeriodPreset) -> Unit,
    onCustom: (LocalDate, LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
  var picking by rememberSaveable { mutableStateOf(false) }
  val chips =
      listOf<Chip<PeriodPreset?>>(
          Chip(PeriodPreset.DAY, stringResource(R.string.history_period_day)),
          Chip(PeriodPreset.MONTH, stringResource(R.string.history_period_month)),
          Chip(PeriodPreset.YEAR, stringResource(R.string.history_period_year)),
          Chip(PeriodPreset.ALL, stringResource(R.string.history_period_all)),
          Chip(null, stringResource(R.string.history_period_custom)),
      )
  ChipRow(
      chips = chips,
      selected = period.preset,
      onSelect = { preset -> if (preset == null) picking = true else onPreset(preset) },
      modifier = modifier.fillMaxWidth(),
      tagFor = { preset ->
        when (preset) {
          PeriodPreset.DAY -> HistoryTags.PERIOD_DAY
          PeriodPreset.MONTH -> HistoryTags.PERIOD_MONTH
          PeriodPreset.YEAR -> HistoryTags.PERIOD_YEAR
          PeriodPreset.ALL -> HistoryTags.PERIOD_ALL
          null -> HistoryTags.PERIOD_CUSTOM
        }
      },
  )
  if (picking) {
    CustomRangeDialog(
        period = period as? Period.Custom,
        onDismiss = { picking = false },
        onConfirm = { from, to ->
          picking = false
          onCustom(from, to)
        },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomRangeDialog(
    period: Period.Custom?,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit,
) {
  val state =
      rememberDateRangePickerState(
          initialSelectedStartDateMillis = period?.from?.utcDayStartMillis(),
          initialSelectedEndDateMillis = period?.to?.utcDayStartMillis(),
      )
  val from = state.selectedStartDateMillis?.let(::localDate)
  val to = state.selectedEndDateMillis?.let(::localDate)
  DatePickerDialog(
      onDismissRequest = onDismiss,
      confirmButton = {
        TextButton(
            enabled = from != null && to != null && !from.isAfter(to),
            onClick = { if (from != null && to != null) onConfirm(from, to) },
        ) {
          Text(stringResource(R.string.history_custom_confirm))
        }
      },
      dismissButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.history_cancel)) }
      },
  ) {
    DateRangePicker(state = state, modifier = Modifier.height(500.dp))
  }
}

/** The picker works in UTC day starts, so the selected day is its UTC calendar date. */
private fun localDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

private fun LocalDate.utcDayStartMillis(): Long =
    atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
