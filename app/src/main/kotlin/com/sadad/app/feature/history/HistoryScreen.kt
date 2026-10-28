package com.sadad.app.feature.history

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sadad.app.R
import com.sadad.app.SadadApplication
import com.sadad.app.export.StatementFormat
import com.sadad.app.feature.lock.AppLock
import com.sadad.app.ui.components.Avatar
import com.sadad.app.ui.components.EmptyState
import com.sadad.app.ui.components.SadadToastHost
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
    title: String? = null,
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
      onDismissExportResult = historyViewModel::dismissExportResult,
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
      title = title,
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
    title: String? = null,
    onDismissExportResult: () -> Unit = {},
) {
  var choosingFormat by rememberSaveable { mutableStateOf(false) }
  Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
      // The Activity board's top bar: the title and «تصدير PDF / Excel» as a pill.
      Row(
          Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 18.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
            title ?: stringResource(R.string.title_history),
            color = sadadPalette.text,
            fontSize = 23.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        ExportPill { choosingFormat = true }
      }
      HistoryTabs(state.tab, onTabSelected)
      when (state.tab) {
        HistoryTab.PEOPLE ->
            PeopleTab(
                state = state,
                onPresetSelected = onPresetSelected,
                onCustomRange = onCustomRange,
                onOpenContact = onOpenContact,
            )
        HistoryTab.ACTIVITY -> ActivityTab(activity = activity, onDismiss = onDismiss)
      }
    }
    // Saved or refused: said once at the foot of whichever tab is open.
    SadadToastHost(
        message = state.error ?: if (state.saved) stringResource(R.string.history_saved) else null,
        onDismiss = onDismissExportResult,
        error = state.error != null,
        modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp),
    )
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
private fun ExportPill(onClick: () -> Unit) {
  val p = sadadPalette
  Surface(
      onClick = onClick,
      color = p.card,
      contentColor = if (p.isDark) p.accent else p.primary,
      shape = RoundedCornerShape(20.dp),
      border = BorderStroke(1.dp, p.line),
      modifier = Modifier.heightIn(min = 40.dp),
  ) {
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Icon(painterResource(R.drawable.ic_payment_in), null, modifier = Modifier.size(18.dp))
      Text(
          stringResource(R.string.history_export_button),
          fontSize = 13.sp,
          fontWeight = FontWeight.Bold,
      )
    }
  }
}

/** The two views as a segmented control on `surface`, the chosen one in `primary`. */
@Composable
private fun HistoryTabs(tab: HistoryTab, onTabSelected: (HistoryTab) -> Unit) {
  val p = sadadPalette
  val description = stringResource(R.string.history_tabs)
  Row(
      Modifier.fillMaxWidth()
          .padding(horizontal = 18.dp, vertical = 4.dp)
          .background(p.surface, RoundedCornerShape(13.dp))
          .padding(4.dp)
          .selectableGroup()
          .semantics { contentDescription = description },
      horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    listOf(
            Triple(HistoryTab.PEOPLE, R.string.history_tab_people, HistoryTags.TAB_PEOPLE),
            Triple(HistoryTab.ACTIVITY, R.string.history_tab_activity, HistoryTags.TAB_ACTIVITY),
        )
        .forEach { (value, label, tag) ->
          val on = value == tab
          val fill by
              animateColorAsState(
                  if (on) p.primary else Color.Transparent,
                  tween(200),
                  label = "tab",
              )
          Box(
              Modifier.weight(1f)
                  .heightIn(min = 48.dp)
                  .background(fill, RoundedCornerShape(10.dp))
                  .selectable(selected = on, role = Role.Tab, onClick = { onTabSelected(value) })
                  .testTag(tag),
              contentAlignment = Alignment.Center,
          ) {
            Text(
                stringResource(label),
                color = if (on) Color.White else p.muted,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
          }
        }
  }
}

@Composable
private fun PeopleTab(
    state: HistoryUiState,
    onPresetSelected: (PeriodPreset) -> Unit,
    onCustomRange: (LocalDate, LocalDate) -> Unit,
    onOpenContact: (String) -> Unit,
) {
  val p = sadadPalette
  val context = LocalContext.current
  LazyColumn(
      modifier = Modifier.fillMaxSize().testTag(HistoryTags.PEOPLE),
      contentPadding = PaddingValues(bottom = 20.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    item {
      Column(
          Modifier.padding(top = 8.dp, bottom = 6.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        PeriodFilter(
            period = state.period,
            onPreset = onPresetSelected,
            onCustom = onCustomRange,
        )
        Text(
            text =
                stringResource(
                    R.string.history_people_heading,
                    state.people.size,
                    periodLabel(context, state.period),
                ),
            color = p.muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
      }
    }
    if (state.people.isEmpty()) {
      item {
        EmptyState(
            title = stringResource(R.string.history_empty_title),
            detail = stringResource(R.string.history_empty_detail),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        )
      }
    } else {
      items(state.people, key = { "person:${it.contactId}" }) { person ->
        PersonRow(person = person, onOpenContact = onOpenContact)
      }
    }
  }
}

/** The board's feed row: initials, the name and what is still open, the last debt and its day. */
@Composable
private fun PersonRow(person: HistoryPerson, onOpenContact: (String) -> Unit) {
  val p = sadadPalette
  val open = stringResource(R.string.history_person_open, person.name)
  Row(
      Modifier.fillMaxWidth()
          .padding(horizontal = 18.dp)
          .heightIn(min = 64.dp)
          .background(p.card, RoundedCornerShape(13.dp))
          .border(1.dp, p.line, RoundedCornerShape(13.dp))
          .clickable(onClickLabel = open) { onOpenContact(person.contactId) }
          .testTag("history:person:${person.contactId}")
          .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Avatar(person.name)
    Column(Modifier.weight(1f)) {
      Text(
          person.name,
          color = p.text,
          fontSize = 15.sp,
          lineHeight = 22.sp,
          fontWeight = FontWeight.Bold,
      )
      Text(
          stringResource(R.string.history_person_remaining, formatCents(person.openBalance)),
          color = p.muted,
          fontSize = 12.sp,
          lineHeight = 18.sp,
      )
    }
    Column(horizontalAlignment = Alignment.End) {
      Text(
          stringResource(R.string.history_person_last_debt),
          color = p.muted,
          fontSize = 11.sp,
          lineHeight = 14.sp,
      )
      Text(
          person.lastDebt?.let { formatCents(it) }
              ?: stringResource(R.string.history_person_no_debt),
          color = if (person.lastDebt != null) p.owes else p.muted,
          fontSize = 15.sp,
          lineHeight = 22.sp,
          fontWeight = FontWeight.Bold,
      )
      Text(formatDate(person.lastMovementAt), color = p.muted, fontSize = 11.sp)
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
