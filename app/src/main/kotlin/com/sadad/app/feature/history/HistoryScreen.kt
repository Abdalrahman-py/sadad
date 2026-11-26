package com.sadad.app.feature.history

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sadad.app.R
import com.sadad.app.export.MovementKind
import com.sadad.app.export.StatementFormat
import com.sadad.app.feature.lock.AppLock
import com.sadad.app.requireGraph
import com.sadad.app.ui.ShowSnackbar
import com.sadad.app.ui.components.EmptyState
import com.sadad.app.ui.components.MoneyText
import com.sadad.app.ui.components.PendingBadge
import com.sadad.app.ui.components.SadadButton
import com.sadad.app.ui.components.SadadButtonStyle
import com.sadad.app.ui.components.formatDate
import com.sadad.app.ui.components.sadadPageBackground
import com.sadad.app.ui.theme.sadadPalette
import com.sadad.ledger.Direction
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object HistoryTags {
  const val ORDER = "history:order"
  const val PERIOD = "history:period"
  const val MOVEMENTS = "history:movements"
  const val EXPORT = "history:export"
  const val LOADING = "history:loading"
  const val PERIOD_DAY = "history:period:day"
  const val PERIOD_MONTH = "history:period:month"
  const val PERIOD_YEAR = "history:period:year"
  const val PERIOD_ALL = "history:period:all"
  const val PERIOD_CUSTOM = "history:period:custom"
}

@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    onOpenContact: (String) -> Unit = {},
    historyViewModel: HistoryViewModel = historyViewModel(),
) {
  val state by historyViewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
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
      onNewestFirst = historyViewModel::setNewestFirst,
      onPresetSelected = historyViewModel::selectPreset,
      onCustomRange = historyViewModel::selectCustom,
      onOpenContact = onOpenContact,
      onDismissExportResult = historyViewModel::dismissExportResult,
      onSaveStatement = { format ->
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
    onNewestFirst: (Boolean) -> Unit,
    onPresetSelected: (PeriodPreset) -> Unit,
    onCustomRange: (LocalDate, LocalDate) -> Unit,
    onOpenContact: (String) -> Unit,
    onSaveStatement: (StatementFormat) -> Unit,
    modifier: Modifier = Modifier,
    onDismissExportResult: () -> Unit = {},
) {
  var choosingFormat by rememberSaveable { mutableStateOf(false) }
  ShowSnackbar(
      message = state.error ?: if (state.saved) stringResource(R.string.history_saved) else null,
      onShown = onDismissExportResult,
  )
  val list = rememberLazyListState()
  Box(modifier.fillMaxSize().sadadPageBackground()) {
    Column(Modifier.fillMaxSize()) {
      LazyColumn(
          state = list,
          modifier = Modifier.fillMaxSize().testTag(HistoryTags.MOVEMENTS),
          // The bottom clears the export button, so the last amount is never under it.
          contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 88.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        item(key = "filters") {
          HistoryFilters(state, onNewestFirst, onPresetSelected, onCustomRange)
        }
        item(key = "summary") {
          if (!state.loaded && state.movements.isEmpty()) {
            // The rows are still being read: a thin bar, not a "0 movements" count that reads as
            // an empty history.
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp).testTag(HistoryTags.LOADING),
                color = sadadPalette.primary,
                trackColor = sadadPalette.line,
            )
          } else {
            Text(
                stringResource(
                    R.string.history_movements_heading,
                    state.movements.size,
                    periodLabel(LocalContext.current, state.period),
                ),
                color = sadadPalette.muted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
          }
        }
        if (state.loaded && state.movements.isEmpty()) {
          item(key = "empty") {
            EmptyState(
                stringResource(R.string.history_empty_title),
                stringResource(R.string.history_empty_detail),
                modifier = Modifier.fillMaxWidth(),
            )
          }
        }
        items(state.movements, key = { "${it.kind}:${it.id}" }) { movement ->
          MovementRow(movement, onOpenContact)
        }
      }
    }
    ExportFab(
        expanded = !list.canScrollBackward,
        onClick = { choosingFormat = true },
        // The physical right in the Arabic layout, as on People.
        modifier = Modifier.align(AbsoluteAlignment.BottomRight).padding(16.dp),
    )
  }
  if (choosingFormat) {
    FormatDialog(
        onDismiss = { choosingFormat = false },
        onChoose = {
          choosingFormat = false
          onSaveStatement(it)
        },
    )
  }
}

@Composable
private fun HistoryFilters(
    state: HistoryUiState,
    onNewestFirst: (Boolean) -> Unit,
    onPreset: (PeriodPreset) -> Unit,
    onCustom: (LocalDate, LocalDate) -> Unit,
) {
  var orderOpen by remember { mutableStateOf(false) }
  var periodOpen by remember { mutableStateOf(false) }
  var picking by rememberSaveable { mutableStateOf(false) }
  val periodText =
      if (state.period is Period.Custom) stringResource(R.string.history_period_custom)
      else periodLabel(LocalContext.current, state.period)
  Row(
      Modifier.fillMaxWidth().height(IntrinsicSize.Min),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(Modifier.weight(1f).fillMaxHeight()) {
      SadadButton(
          stringResource(
              if (state.newestFirst) R.string.contacts_newest else R.string.contacts_oldest
          ),
          onClick = { orderOpen = true },
          style = SadadButtonStyle.SURFACE,
          leading = painterResource(R.drawable.ic_sort),
          modifier = Modifier.fillMaxSize().testTag(HistoryTags.ORDER),
      )
      DropdownMenu(orderOpen, onDismissRequest = { orderOpen = false }) {
        for (newest in listOf(true, false)) {
          DropdownMenuItem(
              text = {
                Text(
                    stringResource(
                        if (newest) R.string.contacts_newest else R.string.contacts_oldest
                    )
                )
              },
              modifier = Modifier.semantics { selected = newest == state.newestFirst },
              onClick = {
                orderOpen = false
                onNewestFirst(newest)
              },
          )
        }
      }
    }
    Box(Modifier.weight(1f).fillMaxHeight()) {
      SadadButton(
          periodText,
          onClick = { periodOpen = true },
          style = SadadButtonStyle.SURFACE,
          trailing = painterResource(R.drawable.ic_chevron_down_wide),
          modifier = Modifier.fillMaxSize().testTag(HistoryTags.PERIOD),
      )
      DropdownMenu(periodOpen, onDismissRequest = { periodOpen = false }) {
        for (preset in PeriodPreset.entries) {
          val tag =
              when (preset) {
                PeriodPreset.DAY -> HistoryTags.PERIOD_DAY
                PeriodPreset.MONTH -> HistoryTags.PERIOD_MONTH
                PeriodPreset.YEAR -> HistoryTags.PERIOD_YEAR
                PeriodPreset.ALL -> HistoryTags.PERIOD_ALL
              }
          val label =
              when (preset) {
                PeriodPreset.DAY -> R.string.history_period_day
                PeriodPreset.MONTH -> R.string.history_period_month
                PeriodPreset.YEAR -> R.string.history_period_year
                PeriodPreset.ALL -> R.string.history_period_all
              }
          DropdownMenuItem(
              text = { Text(stringResource(label)) },
              modifier =
                  Modifier.testTag(tag).semantics { selected = state.period.preset == preset },
              onClick = {
                periodOpen = false
                onPreset(preset)
              },
          )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.history_period_custom)) },
            modifier =
                Modifier.testTag(HistoryTags.PERIOD_CUSTOM).semantics {
                  selected = state.period is Period.Custom
                },
            onClick = {
              periodOpen = false
              picking = true
            },
        )
      }
    }
  }
  if (picking) {
    CustomRangeDialog(
        state.period as? Period.Custom,
        onDismiss = { picking = false },
        onConfirm = { from, to ->
          picking = false
          onCustom(from, to)
        },
    )
  }
}

@Composable
private fun MovementRow(movement: HistoryMovement, onOpen: (String) -> Unit) {
  val p = sadadPalette
  val debt = movement.kind == MovementKind.DEBT
  val color = if (debt) p.owes else if (p.isDark) p.accent else p.primary
  val label =
      when {
        debt && movement.direction == Direction.PAYABLE -> R.string.contacts_kind_payable
        debt -> R.string.contacts_kind_debt
        movement.direction == Direction.PAYABLE -> R.string.contacts_kind_outgoing_payment
        else -> R.string.contacts_kind_payment
      }
  val open = stringResource(R.string.history_person_open, movement.name)
  Card(
      onClick = { onOpen(movement.contactId) },
      colors =
          CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
          ),
      modifier =
          Modifier.fillMaxWidth().testTag("history:movement:${movement.id}").semantics {
            onClick(label = open) {
              onOpen(movement.contactId)
              true
            }
          },
  ) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(
          painterResource(if (debt) R.drawable.ic_payment_out else R.drawable.ic_payment_in),
          contentDescription = null,
          tint = color,
          modifier = Modifier.size(24.dp),
      )
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(movement.name, color = p.text, style = MaterialTheme.typography.titleMedium)
        // The kind and the time on one line.
        Text(
            stringResource(label) +
                " · " +
                formatDate(movement.createdAt) +
                " " +
                TIME_FORMAT.withZone(ZoneId.systemDefault()).format(movement.createdAt),
            color = p.muted,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (movement.pending) PendingBadge()
      }
      MoneyText(movement.amount, color = color, style = MaterialTheme.typography.titleMedium)
    }
  }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

/** Export as the screen's M3 extended FAB; it shrinks to its icon once the list scrolls. */
@Composable
private fun ExportFab(expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val label = stringResource(R.string.history_export_button)
  ExtendedFloatingActionButton(
      text = { Text(label) },
      icon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
      expanded = expanded,
      onClick = onClick,
      modifier =
          modifier.testTag(HistoryTags.EXPORT).clearAndSetSemantics {
            contentDescription = label
            role = Role.Button
          },
  )
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
  val graph = requireGraph()
  return viewModel(
      factory =
          viewModelFactory {
            initializer { HistoryViewModel(graph.statementSource(), createSavedStateHandle()) }
          }
  )
}
