package com.sadad.app.feature.history

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
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
import com.sadad.app.export.MovementKind
import com.sadad.app.export.StatementFormat
import com.sadad.app.feature.lock.AppLock
import com.sadad.app.ui.components.EmptyState
import com.sadad.app.ui.components.MoneyText
import com.sadad.app.ui.components.PendingBadge
import com.sadad.app.ui.components.SadadButton
import com.sadad.app.ui.components.SadadButtonStyle
import com.sadad.app.ui.components.SadadToastHost
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
  Box(modifier.fillMaxSize().sadadPageBackground()) {
    Column(Modifier.fillMaxSize()) {
      Row(
          Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 18.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
            stringResource(R.string.title_history),
            color = sadadPalette.text,
            fontSize = 23.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        ExportPill { choosingFormat = true }
      }
      LazyColumn(
          modifier = Modifier.fillMaxSize().testTag(HistoryTags.MOVEMENTS),
          contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 20.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        item(key = "filters") {
          HistoryFilters(state, onNewestFirst, onPresetSelected, onCustomRange)
        }
        item(key = "summary") {
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
      horizontalArrangement = Arrangement.spacedBy(10.dp),
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
  Row(
      Modifier.fillMaxWidth()
          .heightIn(min = 72.dp)
          .background(p.card, RoundedCornerShape(13.dp))
          .border(1.dp, p.line, RoundedCornerShape(13.dp))
          .clickable(onClickLabel = open) { onOpen(movement.contactId) }
          .testTag("history:movement:${movement.id}")
          .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Box(
        Modifier.size(36.dp).background(if (debt) p.owesSoft else p.soft, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
      Icon(
          painterResource(if (debt) R.drawable.ic_payment_out else R.drawable.ic_payment_in),
          contentDescription = null,
          tint = color,
          modifier = Modifier.size(20.dp),
      )
    }
    Column(Modifier.weight(1f)) {
      Text(
          movement.name,
          color = p.text,
          fontSize = 15.sp,
          lineHeight = 22.sp,
          fontWeight = FontWeight.Bold,
      )
      Text(stringResource(label), color = color, fontSize = 13.sp, lineHeight = 20.sp)
      Text(
          formatDate(movement.createdAt) +
              " · " +
              TIME_FORMAT.withZone(ZoneId.systemDefault()).format(movement.createdAt),
          color = p.muted,
          fontSize = 12.sp,
          lineHeight = 18.sp,
      )
      if (movement.pending) PendingBadge()
    }
    MoneyText(
        movement.amount,
        color = color,
        style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
    )
  }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

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
