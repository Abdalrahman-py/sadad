package com.sadad.app.feature.reports

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sadad.app.R
import com.sadad.app.SadadApplication
import com.sadad.app.export.StatementFormat
import com.sadad.app.feature.history.PeriodFilter
import com.sadad.app.feature.history.PeriodPreset
import com.sadad.app.feature.history.periodLabel
import com.sadad.app.feature.lock.AppLock
import com.sadad.app.ui.components.EmptyState
import com.sadad.app.ui.components.formatCents
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Stable handles the reports tests find the totals and the rows by. */
object ReportsTags {
  const val TOTAL_CONTACTS = "reports:total:contacts"
  const val TOTAL_DEBTS = "reports:total:debts"
  const val TOTAL_PAYMENTS = "reports:total:payments"
  const val OPEN_BALANCE = "reports:open-balance"
  const val SEARCH = "reports:search"
  const val SAVE = "reports:save"
  const val LIST = "reports:list"
  const val ERROR = "reports:error"
  const val SAVED = "reports:saved"

  fun personOpen(contactId: String) = "reports:open:$contactId"

  fun personShare(contactId: String) = "reports:share:$contactId"
}

/**
 * The reports screen: totals for the chosen period, one PDF statement per Contact to open or share,
 * and the full statement saved as PDF or Excel. Leaving the app for a viewer, the share sheet or
 * the document picker must not lock it on return, so each arms [AppLock.skipNextLock].
 */
@Composable
fun ReportsScreen(
    modifier: Modifier = Modifier,
    reportsViewModel: ReportsViewModel = reportsViewModel(),
) {
  val state by reportsViewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  // The picker's contract carries the MIME type, so one launcher per format is what "chosen at
  // save time" means here.
  val savePdf =
      rememberLauncherForActivityResult(
          ActivityResultContracts.CreateDocument(StatementFormat.PDF.mimeType)
      ) { uri ->
        if (uri != null) reportsViewModel.saveStatement(context, uri, StatementFormat.PDF)
      }
  val saveXlsx =
      rememberLauncherForActivityResult(
          ActivityResultContracts.CreateDocument(StatementFormat.XLSX.mimeType)
      ) { uri ->
        if (uri != null) reportsViewModel.saveStatement(context, uri, StatementFormat.XLSX)
      }
  ReportsContent(
      state = state,
      onPreset = reportsViewModel::selectPreset,
      onCustom = reportsViewModel::selectCustom,
      onQueryChange = reportsViewModel::onQueryChange,
      onOpen = { reportsViewModel.openStatement(context, it) },
      onShare = { reportsViewModel.shareStatement(context, it) },
      onSave = { format ->
        // Nothing to write must be said before the picker creates an empty document.
        if (!reportsViewModel.canExport(context)) return@ReportsContent
        // The picker takes the app to another activity; arm the lock skip before it starts.
        AppLock.get(context).skipNextLock()
        val name = context.getString(R.string.reports_statement_name) + "-" + stamp()
        when (format) {
          StatementFormat.PDF -> savePdf.launch(name)
          StatementFormat.XLSX -> saveXlsx.launch(name)
        }
      },
      modifier = modifier,
  )
}

@Composable
fun ReportsContent(
    state: ReportsState,
    onPreset: (PeriodPreset) -> Unit,
    onCustom: (LocalDate, LocalDate) -> Unit,
    onQueryChange: (String) -> Unit,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
    onSave: (StatementFormat) -> Unit,
    modifier: Modifier = Modifier,
) {
  var choosingFormat by rememberSaveable { mutableStateOf(false) }
  val context = LocalContext.current
  Column(modifier.fillMaxSize()) {
    // Pinned above the list: a row action can fail while the list is scrolled anywhere.
    state.error?.let { Banner(it, error = true) }
    if (state.saved) Banner(stringResource(R.string.reports_saved), error = false)
    LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f).testTag(ReportsTags.LIST),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      item {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
          Text(
              text = stringResource(R.string.reports_heading),
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
          )
          Text(
              text = stringResource(R.string.reports_subtitle),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      item { PeriodFilter(period = state.period, onPreset = onPreset, onCustom = onCustom) }
      item {
        Text(
            text = stringResource(R.string.reports_period, periodLabel(context, state.period)),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
      }
      item {
        Metric(
            tag = ReportsTags.TOTAL_CONTACTS,
            title = stringResource(R.string.reports_contacts_title),
            value = state.totals.contacts.toString(),
            detail = stringResource(R.string.reports_contacts_detail),
        )
      }
      item {
        Metric(
            tag = ReportsTags.TOTAL_DEBTS,
            title = stringResource(R.string.reports_debts_title),
            value = formatCents(state.totals.debtTotal),
            detail =
                stringResource(R.string.reports_debts_count, state.totals.debtCount.toString()),
        )
      }
      item {
        Metric(
            tag = ReportsTags.TOTAL_PAYMENTS,
            title = stringResource(R.string.reports_payments_title),
            value = formatCents(state.totals.paymentTotal),
            detail =
                stringResource(
                    R.string.reports_payments_count,
                    state.totals.paymentCount.toString(),
                ),
        )
      }
      item {
        Text(
            text =
                stringResource(
                    R.string.reports_open_balance,
                    formatCents(state.totals.openBalance),
                ),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).testTag(ReportsTags.OPEN_BALANCE),
        )
      }
      item {
        Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
              text = stringResource(R.string.reports_people_heading),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
          )
          Text(
              text = stringResource(R.string.reports_people_detail),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          OutlinedTextField(
              value = state.queryInput,
              onValueChange = onQueryChange,
              singleLine = true,
              placeholder = { Text(stringResource(R.string.reports_search)) },
              modifier = Modifier.fillMaxWidth().testTag(ReportsTags.SEARCH),
          )
        }
      }
      if (state.people.isEmpty()) {
        item {
          val (title, detail) =
              when {
                state.query.isNotBlank() ->
                    R.string.reports_no_results_title to R.string.reports_no_results_detail
                state.registered > 0 ->
                    R.string.reports_period_empty_title to R.string.reports_period_empty_detail
                else -> R.string.reports_empty_title to R.string.reports_empty_detail
              }
          EmptyState(title = stringResource(title), detail = stringResource(detail))
        }
      } else {
        items(state.people, key = { "person:${it.contactId}" }) { person ->
          PersonRow(person = person, enabled = !state.busy, onOpen = onOpen, onShare = onShare)
        }
      }
      item {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
              text = stringResource(R.string.reports_export_heading),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
          )
          Text(
              text = stringResource(R.string.reports_export_detail),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          TextButton(
              onClick = { choosingFormat = true },
              enabled = !state.busy,
              modifier = Modifier.testTag(ReportsTags.SAVE),
          ) {
            Text(stringResource(R.string.reports_save_statement))
          }
        }
      }
    }
  }
  if (choosingFormat) {
    FormatDialog(
        onDismiss = { choosingFormat = false },
        onChoose = { format ->
          choosingFormat = false
          onSave(format)
        },
    )
  }
}

private fun stamp(): String =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.US).format(LocalDateTime.now())

@Composable
private fun reportsViewModel(): ReportsViewModel {
  val app = LocalContext.current.applicationContext as SadadApplication
  return viewModel(
      factory =
          viewModelFactory { initializer { ReportsViewModel(app.graph, createSavedStateHandle()) } }
  )
}
