package com.sadad.app.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sadad.app.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The five period choices, with the custom range opening Material's date range picker. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PeriodFilter(
    period: Period,
    onPreset: (PeriodPreset) -> Unit,
    onCustom: (LocalDate, LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
  var picking by rememberSaveable { mutableStateOf(false) }
  val selected = period.preset
  FlowRow(
      modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Chip(selected == PeriodPreset.DAY, R.string.history_period_day, HistoryTags.PERIOD_DAY) {
      onPreset(PeriodPreset.DAY)
    }
    Chip(selected == PeriodPreset.MONTH, R.string.history_period_month, HistoryTags.PERIOD_MONTH) {
      onPreset(PeriodPreset.MONTH)
    }
    Chip(selected == PeriodPreset.YEAR, R.string.history_period_year, HistoryTags.PERIOD_YEAR) {
      onPreset(PeriodPreset.YEAR)
    }
    Chip(selected == PeriodPreset.ALL, R.string.history_period_all, HistoryTags.PERIOD_ALL) {
      onPreset(PeriodPreset.ALL)
    }
    Chip(selected == null, R.string.history_period_custom, HistoryTags.PERIOD_CUSTOM) {
      picking = true
    }
  }
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

@Composable
private fun Chip(selected: Boolean, label: Int, tag: String, onClick: () -> Unit) {
  FilterChip(
      selected = selected,
      onClick = onClick,
      label = { Text(text = stringResource(label), style = MaterialTheme.typography.labelMedium) },
      modifier = Modifier.testTag(tag),
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRangeDialog(
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
