package com.sadad.app.feature.contacts

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sadad.app.R
import com.sadad.app.export.MovementKind
import com.sadad.app.feature.lock.AppLock
import com.sadad.app.requireGraph
import com.sadad.app.ui.components.Appear
import com.sadad.app.ui.components.BalanceTone
import com.sadad.app.ui.components.ConfirmDialog
import com.sadad.app.ui.components.EmptyState
import com.sadad.app.ui.components.MoneyText
import com.sadad.app.ui.components.PendingBadge
import com.sadad.app.ui.components.SadadButton
import com.sadad.app.ui.components.SadadButtonStyle
import com.sadad.app.ui.components.StickyActions
import com.sadad.app.ui.components.UndoSnackbar
import com.sadad.app.ui.components.balanceWord
import com.sadad.app.ui.components.formatCents
import com.sadad.app.ui.components.formatDate
import com.sadad.app.ui.theme.sadadPalette
import com.sadad.ledger.Cents
import com.sadad.ledger.Direction
import com.sadad.ledger.PaymentMethod
import java.time.YearMonth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal object ContactsTag {
  const val TITLE = "contact:title"
  const val MOVEMENTS = "contact:movements"
  const val MENU = "contact:menu"
}

@Composable
fun ContactDetailScreen(
    contactId: String,
    onBack: () -> Unit,
    onRecord: (contactId: String, payment: Boolean) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val viewModel = contactDetailViewModel(contactId)
  ContactDetailScreen(
      viewModel,
      onBack,
      { onRecord(contactId, false) },
      { onRecord(contactId, true) },
      onEdit,
      modifier,
  )
}

/**
 * The Contact board: who they are and where they stand, then every entry with the balance after it,
 * and the two sticky buttons that record for this person. Edit is a button in the header; WhatsApp,
 * the statement and archive sit in the ⋯ menu.
 */
@Composable
internal fun ContactDetailScreen(
    viewModel: ContactDetailViewModel,
    onBack: () -> Unit,
    onRecordDebt: () -> Unit,
    onRecordPayment: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val state by viewModel.state.collectAsState()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  LaunchedEffect(state.done) { if (state.done) onBack() }
  LaunchedEffect(state.error) {
    if (state.error != null) {
      delay(5_000)
      viewModel.clearError()
    }
  }
  val onWhatsApp: () -> Unit = {
    val number = viewModel.whatsAppNumber()
    if (number == null) {
      viewModel.reportError(context.getString(R.string.contacts_whatsapp_invalid))
    } else {
      try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number")))
        // Armed only after a successful launch: a failed one must not bypass the PIN.
        AppLock.get(context).skipNextLock()
      } catch (_: ActivityNotFoundException) {
        viewModel.reportError(context.getString(R.string.contacts_whatsapp_missing))
      }
    }
  }
  // A supplier is whoever the store owes or has paid out to; the labels follow that side.
  val supplier = state.isSupplier
  val net = state.balance.value - state.payable.value
  val viewConfiguration = LocalViewConfiguration.current
  val movementPressConfiguration =
      remember(viewConfiguration) {
        object : ViewConfiguration by viewConfiguration {
          override val longPressTimeoutMillis = 2_000L
        }
      }

  Column(modifier.fillMaxSize()) {
    ContactTopBar(
        state = state,
        onBack = onBack,
        onWhatsApp = onWhatsApp,
        onShare = { scope.launch { viewModel.share(context) } },
        onEdit = onEdit,
        onArchive = viewModel::requestDelete,
    )
    Box(Modifier.weight(1f)) {
      LazyColumn(
          Modifier.fillMaxSize().testTag(ContactsTag.MOVEMENTS),
          contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        item(key = "identity") { Appear { Identity(state, supplier) } }
        if (state.syncFailure != null) {
          item(key = "sync-failure") {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                  stringResource(R.string.contacts_saved_draft),
                  color = sadadPalette.owes,
                  style = MaterialTheme.typography.titleSmall,
              )
              Text(
                  stringResource(R.string.contacts_sync_failed, state.syncFailure.orEmpty()),
                  color = sadadPalette.owes,
              )
              Text(stringResource(R.string.contacts_draft_kept))
              SadadButton(
                  text = stringResource(R.string.contacts_discard_draft),
                  style = SadadButtonStyle.SURFACE,
                  onClick = viewModel::askDiscard,
                  modifier = Modifier.fillMaxWidth(),
              )
            }
          }
          item(key = "draft-info") { InfoCard(state) }
          items(state.savedDebts.size) { index ->
            val debt = state.savedDebts[index]
            Column(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                  stringResource(R.string.contacts_saved_debt),
                  style = MaterialTheme.typography.titleSmall,
              )
              Text(formatCents(debt.amountCents), color = sadadPalette.owes)
              debt.note?.let { Text(it) }
              debt.dueDate?.let { Text(it) }
            }
          }
        } else if (!state.missing) {
          item(key = "balance") {
            Appear(delayMillis = 60) { BalanceBlock(net, supplier) }
          }
          item(key = "info") { Appear(delayMillis = 100) { InfoCard(state) } }
          item(key = "timeline-head") {
            TimelineHead(state.newestFirst, viewModel::toggleOrder)
          }
          if (state.timeline.isEmpty()) {
            item(key = "empty") {
              EmptyState(
                  title = stringResource(R.string.contacts_no_movements),
                  detail = stringResource(R.string.contacts_no_movements_detail),
                  modifier = Modifier.fillMaxWidth(),
              )
            }
          } else {
            items(
                state.timeline,
                key = { row ->
                  when (row) {
                    is ContactTimelineRow.Month -> "month:${row.month}"
                    is ContactTimelineRow.Movement -> row.movement.id
                  }
                },
                contentType = { row ->
                  when (row) {
                    is ContactTimelineRow.Month -> "month"
                    is ContactTimelineRow.Movement -> "movement"
                  }
                },
            ) { row ->
              when (row) {
                is ContactTimelineRow.Month -> MonthHeader(row.month)
                is ContactTimelineRow.Movement ->
                    CompositionLocalProvider(
                        LocalViewConfiguration provides movementPressConfiguration
                    ) {
                      MovementRow(
                          row.movement,
                          row.balanceAfter,
                          onDelete = { viewModel.askDeleteMovement(row.movement.id) },
                      )
                    }
              }
            }
          }
        }
      }
      state.error?.let { message ->
        Snackbar(modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
          Text(message)
        }
      }
      // The five-second window lives in the ViewModel, so leaving this screen still queues the
      // archive the confirm dialog promised.
      state.undoName?.let { name ->
        UndoSnackbar(
            message = stringResource(R.string.contacts_delete_undo, name),
            onUndo = viewModel::undoDelete,
            onCommit = viewModel::commitDelete,
            onDismiss = viewModel::clearUndo,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
      }
    }
    if (!state.missing && state.loaded && state.syncFailure == null) {
      RecordActions(
          supplier = supplier,
          canPay = state.canRegisterPayments,
          onRecordDebt = onRecordDebt,
          onRecordPayment = onRecordPayment,
      )
    }
  }
  state.settleFirst?.let { amount ->
    AlertDialog(
        onDismissRequest = viewModel::dismissSettleFirst,
        title = { Text(stringResource(R.string.contacts_settle_title)) },
        text = { Text(stringResource(R.string.contacts_settle_message, formatCents(amount))) },
        confirmButton = {
          if (state.balance > Cents.ZERO && state.canRegisterPayments) {
            TextButton(
                onClick = {
                  viewModel.dismissSettleFirst()
                  onRecordPayment()
                }
            ) {
              Text(stringResource(R.string.contacts_record_payment))
            }
          }
        },
        dismissButton = {
          TextButton(onClick = viewModel::dismissSettleFirst) {
            Text(stringResource(R.string.contacts_close))
          }
        },
    )
  }
  if (state.confirmDiscard) {
    ConfirmDialog(
        title = stringResource(R.string.contacts_discard_draft_title),
        message = stringResource(R.string.contacts_discard_draft_message),
        confirmText = stringResource(R.string.contacts_discard_draft),
        dismissText = stringResource(R.string.contacts_cancel),
        onConfirm = viewModel::confirmDiscard,
        onDismiss = viewModel::dismissDiscard,
        destructive = true,
    )
  }
  state.deleting?.let { movement ->
    ConfirmDialog(
        title =
            stringResource(
                when {
                  state.deleteFinal -> R.string.contacts_delete_entry_final_title
                  movement.kind == MovementKind.DEBT -> R.string.contacts_delete_debt_title
                  else -> R.string.contacts_delete_payment_title
                }
            ),
        message =
            if (state.deleteFinal) stringResource(R.string.contacts_delete_entry_final_message)
            else
                stringResource(
                    R.string.contacts_delete_entry_message,
                    formatCents(movement.amount),
                    balanceWord(net).spoken,
                    balanceWord(state.netAfterDeleting(movement)).spoken,
                ),
        confirmText =
            stringResource(
                if (state.deleteFinal) R.string.contacts_delete_entry_final
                else R.string.contacts_delete_entry_next
            ),
        dismissText = stringResource(R.string.contacts_cancel),
        onConfirm = viewModel::confirmDeleteMovement,
        onDismiss = viewModel::dismissDeleteMovement,
        destructive = true,
    )
  }
  if (state.confirmDelete) {
    ConfirmDialog(
        title = stringResource(R.string.contacts_confirm_delete_title),
        message = stringResource(R.string.contacts_confirm_delete_message),
        confirmText = stringResource(R.string.contacts_delete),
        dismissText = stringResource(R.string.contacts_cancel),
        onConfirm = viewModel::confirmDelete,
        onDismiss = viewModel::dismissConfirm,
        destructive = true,
    )
  }
}

/** Back «رجوع إلى الأشخاص» at the start, ⋯ «خيارات أخرى» at the end, as the board's header. */
@Composable
private fun ContactTopBar(
    state: ContactDetailState,
    onBack: () -> Unit,
    onWhatsApp: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
) {
  val p = sadadPalette
  var open by rememberSaveable { mutableStateOf(false) }
  Row(
      Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    RoundButton(R.drawable.ic_back, stringResource(R.string.contacts_back), onBack)
    Box(Modifier.weight(1f))
    if (state.missing || !state.loaded || state.syncFailure != null) return@Row
    RoundButton(R.drawable.ic_pencil, stringResource(R.string.contacts_edit), onEdit)
    Box {
      RoundButton(
          R.drawable.ic_more,
          stringResource(R.string.contacts_more),
          { open = !open },
          filled = open,
          modifier = Modifier.testTag(ContactsTag.MENU),
      )
      DropdownMenu(
          expanded = open,
          onDismissRequest = { open = false },
          containerColor = p.card,
          shape = RoundedCornerShape(12.dp),
          border = BorderStroke(1.dp, p.line),
      ) {
        MenuRow(R.drawable.ic_whatsapp, stringResource(R.string.contacts_whatsapp), p.text) {
          open = false
          onWhatsApp()
        }
        MenuRow(R.drawable.ic_share, stringResource(R.string.contacts_share), p.text) {
          open = false
          onShare()
        }
        if (state.canDeleteContacts) {
          MenuRow(R.drawable.ic_archive, stringResource(R.string.contacts_delete), p.owes) {
            open = false
            onArchive()
          }
        }
      }
    }
  }
}

@Composable
private fun MenuRow(icon: Int, label: String, color: Color, onClick: () -> Unit) {
  DropdownMenuItem(
      text = { Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium) },
      leadingIcon = {
        Icon(painterResource(icon), null, tint = color, modifier = Modifier.size(20.dp))
      },
      onClick = onClick,
      modifier = Modifier.heightIn(min = 48.dp),
  )
}

@Composable
private fun RoundButton(
    icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
  val p = sadadPalette
  Box(
      modifier
          .size(48.dp)
          .clip(CircleShape)
          .background(if (filled) p.surface else Color.Transparent)
          .clickable(role = Role.Button, onClick = onClick)
          .semantics { contentDescription = description },
      contentAlignment = Alignment.Center,
  ) {
    Icon(painterResource(icon), null, tint = p.text, modifier = Modifier.size(24.dp))
  }
}

/** The 56dp initials (round for a customer, squared for a supplier), the name and its line. */
@Composable
private fun Identity(state: ContactDetailState, supplier: Boolean) {
  val p = sadadPalette
  if (state.missing) {
    Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
          stringResource(R.string.contacts_missing),
          color = p.text,
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.testTag(ContactsTag.TITLE),
      )
      Text(stringResource(R.string.contacts_missing_detail), color = p.muted, fontSize = 14.sp)
    }
    return
  }
  val type =
      stringResource(
          if (supplier) R.string.contacts_type_supplier else R.string.contacts_type_customer
      )
  Row(
      Modifier.fillMaxWidth().padding(bottom = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    val initials = state.name.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull() }
    Box(
        Modifier.size(56.dp)
            .background(p.soft, RoundedCornerShape(if (supplier) 16.dp else 28.dp))
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
      Text(
          initials.joinToString(""),
          color = if (p.isDark) p.accent else p.primary,
          style = MaterialTheme.typography.titleLarge,
      )
    }
    Column(Modifier.weight(1f)) {
      Text(
          state.name,
          color = p.text,
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.testTag(ContactsTag.TITLE).semantics { heading() },
      )
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val kind = listOf(type, state.category).filter { it.isNotBlank() }.joinToString(" · ")
        Text(kind, color = p.muted, fontSize = 13.sp, lineHeight = 20.sp)
        if (state.phone.isNotBlank()) {
          Text("·", color = p.muted, fontSize = 13.sp)
          Text(
              state.phone,
              color = p.muted,
              style =
                  TextStyle(
                      fontSize = 13.sp,
                      lineHeight = 20.sp,
                      textDirection = TextDirection.Ltr,
                  ),
          )
        }
      }
    }
  }
}

/** The balance card: arrow, word and amount in the side's colour, then one explanation line. */
@Composable
private fun BalanceBlock(net: Long, supplier: Boolean) {
  val p = sadadPalette
  val word = balanceWord(net)
  val color =
      when (word.tone) {
        BalanceTone.OWES -> p.owes
        BalanceTone.CREDIT -> p.credit
        BalanceTone.SETTLED -> p.muted
      }
  val arrow =
      when (word.tone) {
        BalanceTone.OWES -> R.drawable.ic_balance_owes
        BalanceTone.CREDIT -> R.drawable.ic_balance_credit
        BalanceTone.SETTLED -> R.drawable.ic_check
      }
  Column(
      Modifier.fillMaxWidth()
          .padding(bottom = 12.dp)
          .background(p.card, RoundedCornerShape(16.dp))
          .border(1.dp, p.line, RoundedCornerShape(16.dp))
          .padding(16.dp)
          .animateContentSize(tween(250)),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
        Modifier.clearAndSetSemantics { contentDescription = word.spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(painterResource(arrow), null, tint = color, modifier = Modifier.size(24.dp))
      Text(word.word, color = color, style = MaterialTheme.typography.headlineMedium)
      if (net != 0L) {
        MoneyText(
            Cents(Math.abs(net)),
            style = MaterialTheme.typography.displaySmall,
            color = color,
        )
      }
    }
    if (supplier && net < 0) {
      Text(
          stringResource(R.string.contacts_supplier_explain),
          color = p.muted,
          fontSize = 13.sp,
          lineHeight = 20.sp,
      )
    }
  }
}

/** What is known about the person: number, category and notes; a hint when nothing is. */
@Composable
private fun InfoCard(state: ContactDetailState) {
  val p = sadadPalette
  val rows =
      listOf(
          Triple(R.string.contacts_info_phone, state.phone, true),
          Triple(R.string.contacts_info_category, state.category, false),
          Triple(R.string.contacts_info_note, state.note, false),
      )
  Column(
      Modifier.fillMaxWidth()
          .padding(bottom = 12.dp)
          .background(p.card, RoundedCornerShape(12.dp))
          .border(1.dp, p.line, RoundedCornerShape(12.dp))
          .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    rows.forEach { (label, value, ltr) ->
      Row(
          Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.Top,
      ) {
        Text(
            stringResource(label),
            color = p.muted,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value.ifBlank { stringResource(R.string.contacts_info_none) },
            color = if (value.isBlank()) p.muted else p.text,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            fontWeight = if (value.isBlank()) FontWeight.Normal else FontWeight.Medium,
            style =
                if (ltr && value.isNotBlank()) {
                  LocalTextStyle.current.copy(
                      textDirection = TextDirection.Ltr,
                      fontSize = 14.sp,
                      lineHeight = 21.sp,
                  )
                } else {
                  LocalTextStyle.current
                },
            modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

/** «الحركات», «الرصيد بعد كل حركة», and the order pill that flips newest and oldest first. */
@Composable
private fun TimelineHead(newestFirst: Boolean, onToggle: () -> Unit) {
  val p = sadadPalette
  val label =
      stringResource(if (newestFirst) R.string.contacts_newest else R.string.contacts_oldest)
  val description = stringResource(R.string.contacts_order, label)
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(
          stringResource(R.string.contacts_movements_title),
          color = p.text,
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.semantics { heading() },
      )
      Text(stringResource(R.string.contacts_after_each), color = p.muted, fontSize = 12.sp)
    }
    Surface(
        onClick = onToggle,
        color = p.card,
        contentColor = if (p.isDark) p.accent else p.primary,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, p.line),
        modifier = Modifier.heightIn(min = 40.dp).semantics { contentDescription = description },
    ) {
      Row(
          Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(painterResource(R.drawable.ic_sort), null, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
      }
    }
  }
}

@Composable
private fun MonthHeader(month: YearMonth) {
  Text(
      "${MONTHS[month.monthValue - 1]} ${month.year}",
      color = sadadPalette.muted,
      style = MaterialTheme.typography.labelMedium,
      modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 12.dp, bottom = 2.dp),
  )
}

/** One entry: its icon, what it was and when, its amount, and the balance it left behind. */
@Composable
private fun MovementRow(movement: ContactMovement, afterNet: Long, onDelete: () -> Unit) {
  var showDelete by rememberSaveable(movement.id) { mutableStateOf(false) }
  val deleteLabel =
      stringResource(
          if (movement.kind == MovementKind.DEBT) R.string.contacts_delete_debt_action
          else R.string.contacts_delete_payment_action
      )
  val p = sadadPalette
  val debt = movement.kind == MovementKind.DEBT
  val link = if (p.isDark) p.accent else p.primary
  val title =
      stringResource(
          when {
            debt && movement.direction == Direction.PAYABLE -> R.string.contacts_kind_payable
            debt -> R.string.contacts_kind_debt
            movement.direction == Direction.PAYABLE -> R.string.contacts_kind_outgoing_payment
            else -> R.string.contacts_kind_payment
          }
      ) + (movement.method?.let { " · " + methodLabel(it) } ?: "")
  val meta = listOf(formatDate(movement.createdAt), movement.note).filter { it.isNotBlank() }
  val afterWord = balanceWord(afterNet)
  val afterColor =
      when (afterWord.tone) {
        BalanceTone.OWES -> p.owes
        BalanceTone.CREDIT -> p.credit
        BalanceTone.SETTLED -> p.muted
      }
  Row(
      Modifier.fillMaxWidth()
          .heightIn(min = 64.dp)
          .background(p.card, RoundedCornerShape(12.dp))
          .border(1.dp, p.line, RoundedCornerShape(12.dp))
          .clip(RoundedCornerShape(12.dp))
          .then(
              if (movement.canDelete)
                  Modifier.combinedClickable(
                      onLongClickLabel = deleteLabel,
                      onLongClick = { showDelete = true },
                      onClick = { showDelete = false },
                  )
              else Modifier
          )
          .padding(horizontal = 12.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(
        Modifier.size(36.dp).background(if (debt) p.owesSoft else p.soft, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
      Icon(
          painterResource(if (debt) R.drawable.ic_payment_out else R.drawable.ic_payment_in),
          null,
          tint = if (debt) p.owes else link,
          modifier = Modifier.size(16.dp),
      )
    }
    Column(Modifier.weight(1f)) {
      Text(
          title,
          color = p.text,
          style = MaterialTheme.typography.titleSmall,
      )
      Text(meta.joinToString(" · "), color = p.muted, fontSize = 12.sp, lineHeight = 18.sp)
      movement.settled?.let { settled ->
        Text(
            stringResource(
                R.string.contacts_row_settled_credit,
                formatCents(settled),
                formatCents(movement.credit ?: Cents.ZERO),
            ),
            color = p.credit,
            style = MaterialTheme.typography.bodySmall,
        )
      }
      movement.offsetted?.let { used ->
        Text(
            stringResource(R.string.contacts_row_offset, formatCents(used)),
            color = p.credit,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
      }
      when {
        movement.deletePending ->
            Text(
                stringResource(R.string.contacts_delete_entry_pending),
                color = p.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        movement.canDelete && showDelete ->
            TextButton(
                onClick = {
                  showDelete = false
                  onDelete()
                }
            ) {
              Text(deleteLabel, color = p.owes)
            }
        movement.blockedReason != null ->
            Text(movement.blockedReason, color = p.muted, fontSize = 11.sp, lineHeight = 16.sp)
      }
    }
    Column(horizontalAlignment = Alignment.End) {
      MoneyText(
          movement.amount,
          style = MaterialTheme.typography.titleSmall,
          color = if (debt) p.owes else link,
      )
      if (movement.pending) {
        PendingBadge()
      } else {
        Text(
            listOfNotNull(afterWord.word, afterWord.amount).joinToString(" "),
            color = afterColor,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { contentDescription = afterWord.spoken },
        )
      }
    }
  }
}

/** The two sticky buttons; labels follow the side, and a locked payment says who can unlock it. */
@Composable
private fun RecordActions(
    supplier: Boolean,
    canPay: Boolean,
    onRecordDebt: () -> Unit,
    onRecordPayment: () -> Unit,
) {
  val p = sadadPalette
  StickyActions {
    Surface(
        onClick = onRecordDebt,
        color = p.owesSoft,
        contentColor = p.owes,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.5.dp, p.owes),
        modifier = Modifier.weight(1f).height(52.dp),
    ) {
      Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(painterResource(R.drawable.ic_payment_out), null, modifier = Modifier.size(16.dp))
        Text(
            stringResource(
                if (supplier) R.string.contacts_record_goods else R.string.contacts_record_debt
            ),
            style = MaterialTheme.typography.titleMedium,
        )
      }
    }
    if (canPay) {
      SadadButton(
          text =
              stringResource(
                  if (supplier) R.string.contacts_pay_supplier else R.string.contacts_record_payment
              ),
          onClick = onRecordPayment,
          leading = painterResource(R.drawable.ic_payment_in),
          modifier = Modifier.weight(1f),
      )
    } else {
      Row(
          Modifier.weight(1f)
              .heightIn(min = 52.dp)
              .background(p.surface, RoundedCornerShape(12.dp))
              .padding(horizontal = 12.dp, vertical = 8.dp)
              .semantics(mergeDescendants = true) {},
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
            painterResource(R.drawable.ic_lock),
            null,
            tint = p.muted,
            modifier = Modifier.size(16.dp),
        )
        Text(
            stringResource(R.string.contacts_payments_restricted),
            color = p.muted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
      }
    }
  }
}

private val MONTHS =
    listOf(
        "يناير",
        "فبراير",
        "مارس",
        "أبريل",
        "مايو",
        "يونيو",
        "يوليو",
        "أغسطس",
        "سبتمبر",
        "أكتوبر",
        "نوفمبر",
        "ديسمبر",
    )

@Composable
private fun methodLabel(method: PaymentMethod): String =
    stringResource(
        when (method) {
          PaymentMethod.CASH -> R.string.contacts_method_cash
          PaymentMethod.BANK -> R.string.contacts_method_bank
          PaymentMethod.WALLET -> R.string.contacts_method_wallet
          PaymentMethod.WALLET_JAWWAL -> R.string.contacts_method_jawwal
          PaymentMethod.WALLET_PALPAY -> R.string.contacts_method_palpay
        }
    )

@Composable
private fun contactDetailViewModel(contactId: String): ContactDetailViewModel {
  val graph = requireGraph()
  val context = LocalContext.current.applicationContext
  return viewModel(
      factory =
          viewModelFactory {
            initializer { ContactDetailViewModel(graph.contactDetailSource(), contactId, context) }
          }
  )
}
