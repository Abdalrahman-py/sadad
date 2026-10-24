package com.sadad.app.feature.history

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.sadad.app.feature.lock.AppLock
import com.sadad.app.ui.components.EmptyState
import com.sadad.app.ui.components.SadadCard
import com.sadad.app.ui.components.formatCents
import com.sadad.app.ui.components.formatDate
import com.sadad.app.ui.theme.sadadPalette
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Stable handles the history tests find the tabs and the filter chips by. */
object HistoryTags {
  const val TAB_PEOPLE = "history:tab:people"
  const val TAB_ACTIVITY = "history:tab:activity"
  const val PERIOD_DAY = "history:period:day"
  const val PERIOD_MONTH = "history:period:month"
  const val PERIOD_YEAR = "history:period:year"
  const val PERIOD_ALL = "history:period:all"
  const val PERIOD_CUSTOM = "history:period:custom"
  const val PEOPLE = "history:people"
  const val ACTIVITY = "history:activity"
}

/**
 * The history tab: the Contacts with movements in the chosen period, «فتح التقارير والكشوفات» and
 * the statement save, then a second tab with the ledger's activity log. Opening a Contact and the
 * reports route are callbacks: wiring them in `ui/SadadApp.kt` belongs to the shell.
 */
@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    onOpenContact: (String) -> Unit = {},
    onOpenReports: () -> Unit = {},
    historyViewModel: HistoryViewModel = historyViewModel(),
    activityViewModel: ActivityLogViewModel = activityLogViewModel(),
) {
  val state by historyViewModel.state.collectAsStateWithLifecycle()
  val activity by activityViewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  // The picker's contract carries the MIME type, so one launcher per format is what "chosen at
  // save time" means here.
  val savePdf =
      rememberLauncherForActivityResult(
          ActivityResultContracts.CreateDocument(StatementFormat.PDF.mimeType)
      ) { uri ->
        if (uri != null) historyViewModel.saveStatement(context, uri, StatementFormat.PDF)
      }
  val saveXlsx =
      rememberLauncherForActivityResult(
          ActivityResultContracts.CreateDocument(StatementFormat.XLSX.mimeType)
      ) { uri ->
        if (uri != null) historyViewModel.saveStatement(context, uri, StatementFormat.XLSX)
      }
  HistoryContent(
      state = state,
      activity = activity,
      onTabSelected = historyViewModel::onTabChange,
      onPresetSelected = historyViewModel::selectPreset,
      onCustomRange = historyViewModel::selectCustom,
      onDismiss = activityViewModel::dismiss,
      onOpenContact = onOpenContact,
      onOpenReports = onOpenReports,
      onSaveStatement = { format ->
        // The picker takes the app to another activity; arm the lock skip before it starts.
        AppLock.get(context).skipNextLock()
        val name = context.getString(R.string.history_statement_name)
        when (format) {
          StatementFormat.PDF -> savePdf.launch(name)
          StatementFormat.XLSX -> saveXlsx.launch(name)
        }
      },
      modifier = modifier,
  )
}

@Composable
fun HistoryContent(
    state: HistoryUiState,
    activity: ActivityLogState,
    onTabSelected: (HistoryTab) -> Unit,
    onPresetSelected: (PeriodPreset) -> Unit,
    onCustomRange: (LocalDate, LocalDate) -> Unit,
    onDismiss: (String) -> Unit,
    onOpenContact: (String) -> Unit,
    onOpenReports: () -> Unit,
    onSaveStatement: (StatementFormat) -> Unit,
    modifier: Modifier = Modifier,
) {
  Column(modifier.fillMaxSize()) {
    PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
      Tab(
          selected = state.tab == HistoryTab.PEOPLE,
          onClick = { onTabSelected(HistoryTab.PEOPLE) },
          text = { Text(stringResource(R.string.history_tab_people)) },
          modifier = Modifier.testTag(HistoryTags.TAB_PEOPLE),
      )
      Tab(
          selected = state.tab == HistoryTab.ACTIVITY,
          onClick = { onTabSelected(HistoryTab.ACTIVITY) },
          text = { Text(stringResource(R.string.history_tab_activity)) },
          modifier = Modifier.testTag(HistoryTags.TAB_ACTIVITY),
      )
    }
    when (state.tab) {
      HistoryTab.PEOPLE ->
          PeopleTab(
              state = state,
              onPresetSelected = onPresetSelected,
              onCustomRange = onCustomRange,
              onOpenContact = onOpenContact,
              onOpenReports = onOpenReports,
              onSaveStatement = onSaveStatement,
          )
      HistoryTab.ACTIVITY -> ActivityTab(activity = activity, onDismiss = onDismiss)
    }
  }
}

@Composable
private fun PeopleTab(
    state: HistoryUiState,
    onPresetSelected: (PeriodPreset) -> Unit,
    onCustomRange: (LocalDate, LocalDate) -> Unit,
    onOpenContact: (String) -> Unit,
    onOpenReports: () -> Unit,
    onSaveStatement: (StatementFormat) -> Unit,
) {
  var choosingFormat by rememberSaveable { mutableStateOf(false) }
  val context = LocalContext.current
  LazyColumn(
      modifier = Modifier.fillMaxSize().testTag(HistoryTags.PEOPLE),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text =
                stringResource(
                    R.string.history_people_heading,
                    state.people.size,
                    periodLabel(context, state.period),
                ),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        PeriodFilter(
            period = state.period,
            onPreset = onPresetSelected,
            onCustom = onCustomRange,
        )
        TextButton(onClick = onOpenReports, modifier = Modifier.padding(horizontal = 8.dp)) {
          Text(stringResource(R.string.history_open_reports))
        }
      }
    }
    if (state.people.isEmpty()) {
      item {
        EmptyState(
            title = stringResource(R.string.history_empty_title),
            detail = stringResource(R.string.history_empty_detail),
        )
      }
    } else {
      items(state.people, key = { "person:${it.contactId}" }) { person ->
        PersonRow(person = person, onOpenContact = onOpenContact)
      }
    }
    item {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.history_export_heading),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.history_export_detail),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { choosingFormat = true }) {
          Text(stringResource(R.string.history_save_statement))
        }
        state.error?.let {
          Text(
              text = it,
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.error,
          )
        }
        if (state.saved) {
          Text(
              text = stringResource(R.string.history_saved),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.secondary,
          )
        }
      }
    }
  }
  if (choosingFormat) {
    FormatDialog(
        onDismiss = { choosingFormat = false },
        onChoose = { format ->
          choosingFormat = false
          onSaveStatement(format)
        },
    )
  }
}

@Composable
private fun PersonRow(person: HistoryPerson, onOpenContact: (String) -> Unit) {
  SadadCard(
      onClick = { onOpenContact(person.contactId) },
      modifier =
          Modifier.fillMaxWidth()
              .padding(horizontal = 16.dp)
              .testTag("history:person:${person.contactId}"),
      color = sadadPalette.card,
      contentPadding = PaddingValues(0.dp),
  ) {
    Row(
        modifier = Modifier.padding(15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Column(Modifier.weight(1f)) {
        Text(
            text = person.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text =
                stringResource(R.string.history_person_remaining, formatCents(person.openBalance)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Column(horizontalAlignment = Alignment.End) {
        Text(
            text = stringResource(R.string.history_person_last_debt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text =
                person.lastDebt?.let { formatCents(it) }
                    ?: stringResource(R.string.history_person_no_debt),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = formatDate(person.lastMovementAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun FormatDialog(onDismiss: () -> Unit, onChoose: (StatementFormat) -> Unit) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.history_format_title)) },
      text = { Text(stringResource(R.string.history_export_detail)) },
      confirmButton = {
        TextButton(onClick = { onChoose(StatementFormat.PDF) }) {
          Text(stringResource(R.string.history_format_pdf))
        }
      },
      dismissButton = {
        Row {
          TextButton(onClick = onDismiss) { Text(stringResource(R.string.history_cancel)) }
          TextButton(onClick = { onChoose(StatementFormat.XLSX) }) {
            Text(stringResource(R.string.history_format_xlsx))
          }
        }
      },
  )
}

/** The period's Arabic label, shared by the heading and the statement's header. */
internal fun periodLabel(context: Context, period: Period): String =
    when (period) {
      is Period.Day -> context.getString(R.string.history_period_day)
      is Period.Month -> context.getString(R.string.history_period_month)
      is Period.Year -> context.getString(R.string.history_period_year)
      Period.All -> context.getString(R.string.history_period_all)
      is Period.Custom ->
          context.getString(
              R.string.history_period_custom_range,
              DAY_FORMAT.format(period.from),
              DAY_FORMAT.format(period.to),
          )
    }

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)

@Composable
private fun historyViewModel(): HistoryViewModel {
  val app = LocalContext.current.applicationContext as SadadApplication
  return viewModel(
      factory =
          viewModelFactory {
            initializer { HistoryViewModel(app.graph, createSavedStateHandle()) }
          }
  )
}

@Composable
private fun activityLogViewModel(): ActivityLogViewModel {
  val app = LocalContext.current.applicationContext as SadadApplication
  return viewModel(factory = viewModelFactory { initializer { ActivityLogViewModel(app.graph) } })
}
