package com.sadad.app.feature.quickadd

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sadad.app.R
import com.sadad.app.requireGraph
import com.sadad.app.ui.components.BalanceTone
import com.sadad.app.ui.components.BalanceWord
import com.sadad.app.ui.components.Chip as ComponentChip
import com.sadad.app.ui.components.ChipFlow
import com.sadad.app.ui.components.KeypadKey
import com.sadad.app.ui.components.SHEKEL
import com.sadad.app.ui.components.SadadButton
import com.sadad.app.ui.components.SadadButtonStyle
import com.sadad.app.ui.components.SadadKeypad
import com.sadad.app.ui.components.SadadTextField
import com.sadad.app.ui.components.balanceWord
import com.sadad.app.ui.components.formatAmount
import com.sadad.app.ui.components.sadadPageBackground
import com.sadad.app.ui.theme.sadadPalette
import com.sadad.ledger.ContactKind
import com.sadad.ledger.PaymentMethod
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The handles the screen tests and the a11y sweep hold on to. */
object QuickAddTags {
  const val SEARCH = "quickadd:search"
  const val CLEAR = "quickadd:clear"
  const val PEOPLE = "quickadd:people"
  const val ADD_NEW = "quickadd:add-new"
  const val BLOCKED = "quickadd:blocked"
  const val QUEUED = "quickadd:queued"
  const val AMOUNT = "quickadd:amount"
  const val PREVIEW = "quickadd:preview"
  const val ERROR = "quickadd:error"
  const val DUE_CHIP = "quickadd:due-chip"
  const val NOTE_CHIP = "quickadd:note-chip"
  const val DUE_DIALOG = "quickadd:due-dialog"
  const val NOTE_DIALOG = "quickadd:note-dialog"
  const val SAVE = "quickadd:save"
  const val KEYPAD = "quickadd:keypad"
  const val BACK = "quickadd:back"
  const val DEBT = "quickadd:mode-debt"
  const val PAY = "quickadd:mode-pay"
  const val DONE = "quickadd:done"
  const val UNDO = "quickadd:undo"
  const val ANOTHER = "quickadd:another"
  const val ANOMALY = "quickadd:anomaly"

  fun person(id: String) = "quickadd:person-$id"

  fun key(label: String) = "quickadd:key-$label"
}

/** Records for a saved person, or for a new one created inside the flow with their first debt. */
@Composable
fun QuickAddScreen(
    contactId: String?,
    payment: Boolean,
    onClose: () -> Unit,
    onOpenContact: (String) -> Unit,
    onOpenTrash: () -> Unit,
    viewModel: QuickAddViewModel = quickAddViewModel(contactId, payment),
) {
  val state by viewModel.state.collectAsState()
  val context = LocalContext.current
  val p = sadadPalette
  // A picked phone number of someone in the archive: they are restored from the trash, not here.
  LaunchedEffect(state.openTrash) {
    if (state.openTrash) {
      viewModel.onTrashHandled()
      onOpenTrash()
    }
  }

  Box(Modifier.fillMaxSize().sadadPageBackground()) {
    when (state.step) {
      QuickAddStep.PICKER -> PickerStep(state, viewModel, onClose)
      QuickAddStep.NEW_PERSON -> NewPersonStep(state, viewModel)
      QuickAddStep.AMOUNT ->
          AmountStep(state, viewModel, context, onClose, fromContact = contactId != null)
      QuickAddStep.DONE -> DoneStep(state, viewModel, context, onClose, onOpenContact)
    }
    val anomaly = state.anomaly
    if (anomaly != null) {
      AlertDialog(
          modifier = Modifier.testTag(QuickAddTags.ANOMALY),
          onDismissRequest = viewModel::dismissAnomaly,
          containerColor = p.card,
          title = {
            Text(stringResource(R.string.quickadd_anomaly_title))
          },
          text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(stringResource(R.string.quickadd_anomaly_body))
              Text(
                  stringResource(
                      R.string.quickadd_anomaly_sentence,
                      viewModel.sentence(context),
                  ),
              )
            }
          },
          confirmButton = {
            TextButton(
                onClick = {
                  viewModel.confirmAnomaly()
                  viewModel.save(context)
                }
            ) {
              Text(stringResource(R.string.quickadd_anomaly_confirm))
            }
          },
          dismissButton = {
            TextButton(onClick = viewModel::dismissAnomaly) {
              Text(stringResource(R.string.quickadd_anomaly_edit))
            }
          },
      )
    }
  }
}

@Composable
private fun quickAddViewModel(contactId: String?, payment: Boolean): QuickAddViewModel {
  val graph = requireGraph()
  return viewModel(
      factory =
          viewModelFactory {
            initializer {
              QuickAddViewModel(
                  graph.quickAddSource(),
                  contactId,
                  payment,
                  createSavedStateHandle(),
              )
            }
          }
  )
}

/* ---------------------------------------------------------------- picker */

@Composable
private fun PickerStep(
    state: QuickAddState,
    model: QuickAddViewModel,
    onClose: () -> Unit,
) {
  val p = sadadPalette
  Column(
      Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(
        Modifier.fillMaxWidth().height(60.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      RoundIconButton(description = stringResource(R.string.quickadd_close), onClick = onClose) {
        XGlyph(p.text)
      }
      Text(
          stringResource(R.string.quickadd_picker_title),
          style = MaterialTheme.typography.titleLarge,
          color = p.text,
      )
      Spacer(Modifier.width(44.dp))
    }

    SearchField(
        query = state.query,
        onQuery = model::onQueryChange,
        onClear = model::clearQuery,
    )
    // A new person is someone to record a debt for; the payment flow has nobody new to pay.
    if (!state.payment) FromContactsRow(model)
    if (state.blocked) BlockedCard()
    state.error?.let {
      Text(
          it,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag(QuickAddTags.ERROR),
      )
    }

    Text(
        text = listLabelText(state),
        color = p.muted,
        style = MaterialTheme.typography.bodySmall,
    )

    LazyColumn(
        Modifier.fillMaxWidth().weight(1f).testTag(QuickAddTags.PEOPLE),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(state.people, key = { it.id }) { person ->
        PersonRow(person, onClick = { model.pick(person.id) })
      }
      if (state.query.isNotBlank() && state.people.isEmpty()) {
        item {
          Text(
              stringResource(R.string.quickadd_no_results),
              color = p.muted,
              fontSize = 13.sp,
              modifier = Modifier.padding(vertical = 8.dp),
          )
        }
      }
      // A new person is someone to record a debt for; the payment flow has nobody new to pay.
      if (state.query.isNotBlank() && !state.payment) {
        item { AddNewRow(name = state.query.trim(), onClick = model::addNewPerson) }
      }
    }
  }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onClear: () -> Unit) {
  val p = sadadPalette
  val hint = stringResource(R.string.quickadd_search_hint)
  val focus = remember { FocusRequester() }
  val keyboard = LocalSoftwareKeyboardController.current
  // The field owns its text: a value that comes back through the view model's flow arrives a frame
  // late, and the cursor jumps while the owner types. Only a reset to empty (clear, «تسجيل آخر»)
  // flows back in.
  var text by rememberSaveable { mutableStateOf(query) }
  LaunchedEffect(query) { if (query.isEmpty()) text = "" }
  LaunchedEffect(Unit) {
    focus.requestFocus()
    keyboard?.show()
  }
  Surface(
      color = p.card,
      shape = RoundedCornerShape(12.dp),
      border = BorderStroke(2.dp, if (p.isDark) p.accent else p.primary),
      modifier = Modifier.fillMaxWidth().height(52.dp),
  ) {
    Row(
        Modifier.fillMaxSize().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      SearchGlyph(p.muted)
      BasicTextField(
          value = text,
          onValueChange = {
            text = it
            onQuery(it)
          },
          singleLine = true,
          textStyle = LocalTextStyle.current.copy(fontSize = 15.sp, color = p.text),
          cursorBrush = SolidColor(p.primary),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
          modifier =
              Modifier.weight(1f).focusRequester(focus).testTag(QuickAddTags.SEARCH).semantics {
                contentDescription = hint
              },
          decorationBox = { inner ->
            Box(Modifier.fillMaxWidth()) {
              if (text.isEmpty()) Text(hint, color = p.muted, fontSize = 15.sp)
              inner()
            }
          },
      )
      if (text.isNotEmpty()) {
        RoundIconButton(
            description = stringResource(R.string.quickadd_clear_search),
            size = 40,
            tag = QuickAddTags.CLEAR,
            onClick = {
              text = ""
              onClear()
            },
        ) {
          XGlyph(p.muted, size = 18)
        }
      }
    }
  }
}

@Composable
private fun BlockedCard() {
  val p = sadadPalette
  Surface(
      color = p.creditSoft,
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier.fillMaxWidth().testTag(QuickAddTags.BLOCKED),
  ) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text(
            stringResource(R.string.quickadd_blocked_title),
            style = MaterialTheme.typography.titleSmall,
            fontSize = 14.sp,
            color = p.text,
        )
        Text(
            stringResource(R.string.quickadd_blocked_body),
            fontSize = 12.sp,
            color = p.muted,
        )
      }
    }
  }
}

@Composable
private fun PersonRow(person: QuickAddPerson, onClick: () -> Unit) {
  val p = sadadPalette
  val supplier = person.kind == ContactKind.SUPPLIER
  Surface(
      color = p.card,
      shape = RoundedCornerShape(12.dp),
      border = BorderStroke(1.dp, p.line),
      modifier =
          Modifier.fillMaxWidth()
              .heightIn(min = 64.dp)
              .testTag(QuickAddTags.person(person.id))
              .clip(RoundedCornerShape(12.dp))
              .clickable(onClick = onClick)
              .semantics { role = Role.Button },
  ) {
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Initials(person.initials, supplier)
      Column(Modifier.weight(1f)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(person.name, style = MaterialTheme.typography.titleSmall, color = p.text)
          if (supplier) SupplierBadge()
        }
        if (person.detailLine.isNotEmpty()) {
          Text(person.detailLine, fontSize = 12.sp, color = p.muted)
        }
      }
      BalanceWord(person.balance)
    }
  }
}

@Composable
private fun SupplierBadge() {
  val p = sadadPalette
  Surface(color = p.surface, shape = RoundedCornerShape(8.dp)) {
    Text(
        stringResource(R.string.quickadd_supplier_badge),
        color = p.muted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
    )
  }
}

@Composable
private fun AddNewRow(name: String, onClick: () -> Unit) {
  val p = sadadPalette
  val link = if (p.isDark) p.accent else p.primary
  Surface(
      color = Color.Transparent,
      shape = RoundedCornerShape(12.dp),
      modifier =
          Modifier.fillMaxWidth()
              .heightIn(min = 64.dp)
              .dashedBorder(p.muted, 12.dp)
              .testTag(QuickAddTags.ADD_NEW)
              .clip(RoundedCornerShape(12.dp))
              .clickable(onClick = onClick)
              .semantics { role = Role.Button },
  ) {
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
          Modifier.size(40.dp).clip(CircleShape).background(p.soft),
          contentAlignment = Alignment.Center,
      ) {
        PlusGlyph(link)
      }
      Text(
          stringResource(R.string.quickadd_new_result, name),
          style = MaterialTheme.typography.titleSmall,
          fontSize = 15.sp,
          color = link,
      )
    }
  }
}

/* ---------------------------------------------------------------- amount */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AmountStep(
    state: QuickAddState,
    model: QuickAddViewModel,
    context: Context,
    onClose: () -> Unit,
    fromContact: Boolean,
) {
  val p = sadadPalette
  var choosingDue by rememberSaveable { mutableStateOf(false) }
  var writingNote by rememberSaveable { mutableStateOf(false) }
  val person = state.selected
  val supplier = person?.kind == ContactKind.SUPPLIER
  Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 20.dp)) {
    Row(
        Modifier.fillMaxWidth().height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      RoundIconButton(
          description = stringResource(R.string.quickadd_change_person),
          tag = QuickAddTags.BACK,
          // Opened from a person's page there is no one to change: back returns to that page.
          onClick = if (fromContact) onClose else model::backToPicker,
      ) {
        BackGlyph(p.text)
      }
      Initials(person?.initials ?: "؟", supplier == true)
      Column(Modifier.weight(1f)) {
        Text(
            person?.name.orEmpty(),
            style = MaterialTheme.typography.titleSmall,
            fontSize = 16.sp,
            color = p.text,
        )
        val balance = balanceWord(person?.net?.value ?: 0L)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
              text =
                  stringResource(
                      if (supplier == true) R.string.quickadd_supplier_badge
                      else R.string.quickadd_customer
                  ) + " · " + balance.word,
              fontSize = 13.sp,
              color = p.muted,
          )
          if (balance.amount != null) {
            Text(
                balance.amount,
                style = MaterialTheme.typography.titleSmall,
                color =
                    when (balance.tone) {
                      BalanceTone.OWES -> p.owes
                      BalanceTone.CREDIT -> p.credit
                      BalanceTone.SETTLED -> p.muted
                    },
            )
          }
        }
      }
    }

    ModeToggle(state, model, supplier == true)
    Spacer(Modifier.height(12.dp))

    Column(
        Modifier.fillMaxWidth().weight(1f),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      // The amount and the keypad read left to right, as the board draws them, inside an RTL page.
      CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(verticalAlignment = Alignment.Bottom) {
          Text(SHEKEL, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = p.text)
          Spacer(Modifier.width(8.dp))
          Text(
              text =
                  if (state.terms.isEmpty()) typedAmountLabel(state.amount)
                  else formatAmount(state.total),
              fontSize = 52.sp,
              fontWeight = FontWeight.SemiBold,
              color = if (state.terms.isEmpty() && state.amount.isEmpty()) Ghost else p.text,
              modifier = Modifier.testTag(QuickAddTags.AMOUNT),
          )
        }
      }
      if (state.terms.isNotEmpty()) {
        // «12 + 30 +»: what the total is made of, read left to right like the amount.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
          Text(
              text =
                  (if (state.amount.isEmpty()) state.terms else state.terms + state.amount)
                      .joinToString(" + ")
                      .let { if (state.amount.isEmpty()) "$it +" else it },
              fontSize = 16.sp,
              color = p.muted,
              maxLines = 1,
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
          )
        }
      }
      val amount = state.total
      val over = state.payment && amount > state.overBalance
      Surface(
          color = if (over) p.creditSoft else Color.Transparent,
          shape = RoundedCornerShape(12.dp),
      ) {
        Text(
            text = model.preview(context),
            fontSize = 14.sp,
            color = if (over) p.credit else p.muted,
            modifier =
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag(QuickAddTags.PREVIEW),
        )
      }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (!state.payment) {
        // A due date belongs to what a customer owes; a supplier's goods have none.
        if (supplier != true) {
          Chip(
              label =
                  state.dueDate?.let { stringResource(R.string.quickadd_due_on, it) }
                      ?: stringResource(R.string.quickadd_due),
              active = state.dueDate != null,
              onClick = { choosingDue = true },
              tag = QuickAddTags.DUE_CHIP,
          )
        }
        Chip(
            label =
                state.note.takeIf { it.isNotBlank() }?.let { it.trim().take(NOTE_CHIP_CHARS) }
                    ?: stringResource(R.string.quickadd_note),
            active = state.note.isNotEmpty(),
            onClick = { writingNote = true },
            tag = QuickAddTags.NOTE_CHIP,
        )
      } else {
        for (method in QUICK_METHODS) {
          Chip(
              label = methodLabel(method),
              active = state.method == method,
              onClick = { model.onMethodChange(method) },
          )
        }
      }
      Spacer(Modifier.weight(1f))
    }
    Spacer(Modifier.height(12.dp))

    state.error?.let { message ->
      Text(
          text = message,
          fontSize = 13.sp,
          color = p.credit,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag(QuickAddTags.ERROR),
      )
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
      Keypad(onKey = model::press, onLongKey = { model.press(".") }, onDelete = model::delete)
    }

    if (choosingDue) {
      DueDateDialog(
          current = state.dueDate,
          onPick = {
            model.onDueDateChange(it)
            choosingDue = false
          },
          onDismiss = { choosingDue = false },
      )
    }
    if (writingNote) {
      NoteDialog(
          current = state.note,
          onSave = {
            model.onNoteChange(it)
            writingNote = false
          },
          onDismiss = { writingNote = false },
      )
    }

    SaveButton(
        text =
            when {
              state.blocked -> stringResource(R.string.quickadd_save_blocked)
              state.amount.isEmpty() -> stringResource(R.string.quickadd_preview_empty)
              else -> model.sentence(context)
            },
        payment = state.payment,
        enabled = state.canSave,
        onClick = { model.save(context) },
    )
  }
}

@Composable
private fun ModeToggle(state: QuickAddState, model: QuickAddViewModel, supplier: Boolean) {
  val p = sadadPalette
  Surface(
      color = p.surface,
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier.fillMaxWidth(),
  ) {
    Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      ModeCell(
          label =
              stringResource(
                  if (supplier) R.string.quickadd_debt_supplier else R.string.quickadd_debt_customer
              ),
          chosen = !state.payment,
          chosenBackground = p.owesSoft,
          chosenInk = p.owes,
          tag = QuickAddTags.DEBT,
          onClick = model::toDebt,
          modifier = Modifier.weight(1f),
      )
      // A person created in this flow has nothing to pay yet.
      if (!state.restricted && state.newPerson == null) {
        ModeCell(
            label =
                stringResource(
                    if (supplier) R.string.quickadd_pay_supplier else R.string.quickadd_pay_customer
                ),
            chosen = state.payment,
            chosenBackground = p.soft,
            chosenInk = if (p.isDark) p.accent else p.primary,
            tag = QuickAddTags.PAY,
            onClick = model::toPayment,
            modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

@Composable
private fun ModeCell(
    label: String,
    chosen: Boolean,
    chosenBackground: Color,
    chosenInk: Color,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val p = sadadPalette
  Surface(
      color = if (chosen) chosenBackground else Color.Transparent,
      shape = RoundedCornerShape(12.dp),
      border = if (chosen) BorderStroke(1.5.dp, chosenInk) else null,
      modifier =
          modifier
              .height(44.dp)
              .testTag(tag)
              .clip(RoundedCornerShape(12.dp))
              .clickable(onClick = onClick)
              .semantics { role = Role.Button },
  ) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(
          label,
          color = if (chosen) chosenInk else p.muted,
          style = MaterialTheme.typography.labelLarge,
      )
    }
  }
}

/** The shared keypad, tagged per key as the quick add tests find them. */
@Composable
private fun Keypad(onKey: (String) -> Unit, onLongKey: (String) -> Unit, onDelete: () -> Unit) {
  val plus = stringResource(R.string.quickadd_key_plus)
  val keys =
      listOf("1", "2", "3", "4", "5", "6", "7", "8", "9").map { KeypadKey.Char(it) } +
          listOf(
              // Tap adds the typed amount to the total; a long press types the decimal mark.
              KeypadKey.Char("+", plus, longLabel = "."),
              KeypadKey.Char("0"),
              KeypadKey.Delete(stringResource(R.string.quickadd_key_delete)),
          )
  SadadKeypad(
      keys = keys,
      onChar = onKey,
      onLongChar = onLongKey,
      onDelete = onDelete,
      modifier = Modifier.testTag(QuickAddTags.KEYPAD),
      tagFor = { key ->
        when (key) {
          is KeypadKey.Char -> QuickAddTags.key(key.label)
          is KeypadKey.Delete -> QuickAddTags.key("del")
          else -> null
        }
      },
  )
  Spacer(Modifier.height(12.dp))
}

@Composable
private fun Chip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    tag: String? = null,
) {
  val p = sadadPalette
  Surface(
      color = if (active) p.soft else p.card,
      shape = RoundedCornerShape(24.dp),
      border = BorderStroke(1.dp, p.line),
      modifier =
          Modifier.height(44.dp)
              .then(if (tag == null) Modifier else Modifier.testTag(tag))
              .clip(RoundedCornerShape(24.dp))
              .clickable(onClick = onClick),
  ) {
    Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
      Text(
          label,
          color = if (p.isDark) p.accent else p.primary,
          style = MaterialTheme.typography.labelLarge,
      )
    }
  }
}

/** «الاستحقاق»: today or a later day, from the calendar; «إزالة» clears it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueDateDialog(current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
  val today = LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
  val state =
      rememberDatePickerState(
          initialSelectedDateMillis =
              current
                  ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                  ?.atStartOfDay()
                  ?.toInstant(ZoneOffset.UTC)
                  ?.toEpochMilli(),
          selectableDates =
              object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= today
              },
      )
  val picked = state.selectedDateMillis
  DatePickerDialog(
      modifier = Modifier.testTag(QuickAddTags.DUE_DIALOG),
      onDismissRequest = onDismiss,
      confirmButton = {
        RightToLeft {
          TextButton(
              enabled = picked != null,
              onClick = {
                if (picked != null) {
                  onPick(
                      Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate().toString()
                  )
                }
              },
          ) {
            Text(stringResource(R.string.quickadd_due_confirm))
          }
        }
      },
      dismissButton = {
        RightToLeft {
          Row {
            if (current != null) {
              TextButton(onClick = { onPick(null) }) {
                Text(stringResource(R.string.quickadd_due_clear))
              }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.quickadd_cancel)) }
          }
        }
      },
  ) {
    RightToLeft { DatePicker(state = state) }
  }
}

/**
 * A dialog is its own window: its first frame takes the device's direction, and the page's right to
 * left reaches it one frame later, so the picker drew left to right for a moment. Set here, the
 * direction is right from the first frame.
 */
@Composable
private fun RightToLeft(content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content)
}

/** «ملاحظة»: a line saying what the entry was for, kept with it in the ledger. */
@Composable
private fun NoteDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
  var text by remember { mutableStateOf(current) }
  val suggestions =
      stringArrayResource(R.array.quickadd_note_suggestions)
          .map { it.trim() }
          .filter { it.isNotEmpty() }
  AlertDialog(
      modifier = Modifier.testTag(QuickAddTags.NOTE_DIALOG),
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.quickadd_note)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          SadadTextField(
              value = text,
              onValueChange = { text = it.take(NOTE_MAX_CHARS) },
              placeholder = stringResource(R.string.quickadd_note_hint),
              modifier = Modifier.fillMaxWidth(),
          )
          if (suggestions.isNotEmpty()) {
            ChipFlow(
                chips = suggestions.map { ComponentChip(it, it) },
                selected = "",
                onSelect = { word ->
                  // A tap fills the line: «كولا» on its own, a second tap joins with « و».
                  text =
                      when {
                        text.isBlank() -> word
                        text.split(" و").any { it.trim() == word } -> text
                        else -> "${text.trim()} و$word"
                      }.take(NOTE_MAX_CHARS)
                },
            )
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { onSave(text.trim()) }) {
          Text(stringResource(R.string.quickadd_note_save))
        }
      },
      dismissButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.quickadd_cancel)) }
      },
  )
}

private const val NOTE_MAX_CHARS = 200
private const val NOTE_CHIP_CHARS = 14

/* ---------------------------------------------------------------- done */

@Composable
private fun DoneStep(
    state: QuickAddState,
    model: QuickAddViewModel,
    context: Context,
    onClose: () -> Unit,
    onOpenContact: (String) -> Unit,
) {
  val p = sadadPalette
  val done = state.done ?: return
  // System back is a way out like the two buttons below: whatever is held is queued, not dropped.
  BackHandler {
    model.leave(context)
    onClose()
  }
  val person = state.selected
  val supplier = person?.kind == ContactKind.SUPPLIER
  val title =
      stringResource(
          when {
            supplier == true && !state.payment -> R.string.quickadd_done_debt_supplier
            supplier == true -> R.string.quickadd_done_payment_supplier
            state.payment -> R.string.quickadd_done_payment_customer
            else -> R.string.quickadd_done_debt_customer
          }
      )
  Column(
      Modifier.fillMaxSize()
          .padding(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 24.dp)
          .testTag(QuickAddTags.DONE),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(
        Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Box(
          Modifier.size(84.dp).clip(CircleShape).background(p.soft),
          contentAlignment = Alignment.Center,
      ) {
        CheckGlyph(if (p.isDark) p.accent else p.primary)
      }
      Text(title, style = MaterialTheme.typography.headlineMedium, color = p.text)
      Text(
          withoutVerb(done.sentence),
          fontSize = 15.sp,
          lineHeight = 24.sp,
          color = p.muted,
          textAlign = TextAlign.Center,
      )
      Surface(
          color = p.card,
          shape = RoundedCornerShape(12.dp),
          border = BorderStroke(1.dp, p.line),
      ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          val balance = balanceWord(done.net.value)
          Text(
              stringResource(R.string.quickadd_net_label, done.name),
              fontSize = 15.sp,
              color = p.text,
              modifier = Modifier.weight(1f, fill = false),
          )
          Text(
              text = listOfNotNull(balance.word, balance.amount).joinToString(" "),
              style = MaterialTheme.typography.titleSmall,
              color =
                  when (balance.tone) {
                    BalanceTone.OWES -> p.owes
                    BalanceTone.CREDIT -> p.credit
                    BalanceTone.SETTLED -> p.muted
                  },
          )
        }
      }
    }

    if (done.offline) {
      Text(
          stringResource(R.string.quickadd_done_offline),
          fontSize = 13.sp,
          color = p.muted,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth().testTag(QuickAddTags.QUEUED),
      )
    }

    // Once the entry is queued there is nothing to undo here, so the offer goes away with it.
    if (done.secondsLeft > 0) {
      SadadButton(
          text =
              stringResource(R.string.quickadd_undo) +
                  " " +
                  stringResource(R.string.quickadd_undo_seconds, done.secondsLeft),
          onClick = { model.undo(context) },
          style = SadadButtonStyle.SURFACE,
          modifier = Modifier.fillMaxWidth().height(48.dp).testTag(QuickAddTags.UNDO),
      )
    }
    SadadButton(
        text = stringResource(R.string.quickadd_another),
        onClick = { model.another(context) },
        modifier = Modifier.fillMaxWidth().height(56.dp).testTag(QuickAddTags.ANOTHER),
    )
    // One way on besides «تسجيل آخر»: the bar below already leads home.
    if (person != null) {
      SadadButton(
          text = stringResource(R.string.quickadd_open_contact, firstWord(person.name)),
          onClick = {
            model.leave(context)
            onOpenContact(person.id)
          },
          style = SadadButtonStyle.SOFT,
          modifier = Modifier.fillMaxWidth().height(48.dp),
      )
    }
  }
}

/**
 * The board's save button: `--owesBtn` for a debt, `--primary` for a payment, `--soft` when idle.
 */
@Composable
private fun SaveButton(text: String, payment: Boolean, enabled: Boolean, onClick: () -> Unit) {
  val p = sadadPalette
  val fill =
      when {
        !enabled -> p.soft
        payment -> p.primary
        else -> p.owes
      }
  val ink =
      when {
        !enabled -> p.muted
        payment || p.isDark -> if (payment) Color.White else p.bg
        else -> Color.White
      }
  Surface(
      onClick = onClick,
      enabled = enabled,
      shape = RoundedCornerShape(12.dp),
      color = fill,
      contentColor = ink,
      modifier =
          Modifier.fillMaxWidth().height(56.dp).testTag(QuickAddTags.SAVE).semantics {
            role = Role.Button
          },
  ) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(text, style = MaterialTheme.typography.titleMedium, color = ink)
    }
  }
}

/* --------------------------------------------------------- small parts */

@Composable
private fun RoundIconButton(
    description: String,
    onClick: () -> Unit,
    size: Int = 44,
    tag: String? = null,
    content: @Composable () -> Unit,
) {
  Box(
      Modifier.size(size.dp)
          .clip(CircleShape)
          .then(if (tag != null) Modifier.testTag(tag) else Modifier)
          .clickable(onClick = onClick)
          .semantics {
            contentDescription = description
            role = Role.Button
          },
      contentAlignment = Alignment.Center,
  ) {
    content()
  }
}

@Composable
private fun Initials(initials: String, supplier: Boolean) {
  val p = sadadPalette
  val shape = if (supplier) RoundedCornerShape(12.dp) else CircleShape
  Box(Modifier.size(40.dp).clip(shape).background(p.soft), contentAlignment = Alignment.Center) {
    Text(
        initials,
        color = if (p.isDark) p.accent else p.primary,
        style = MaterialTheme.typography.titleSmall,
    )
  }
}

@Composable
private fun XGlyph(color: Color, size: Int = 24) {
  Canvas(Modifier.size(size.dp)) {
    val stroke = this.size.minDimension * 0.09f
    drawLine(
        color,
        Offset(this.size.width * 0.25f, this.size.height * 0.25f),
        Offset(this.size.width * 0.75f, this.size.height * 0.75f),
        stroke,
    )
    drawLine(
        color,
        Offset(this.size.width * 0.75f, this.size.height * 0.25f),
        Offset(this.size.width * 0.25f, this.size.height * 0.75f),
        stroke,
    )
  }
}

@Composable
private fun PlusGlyph(color: Color) {
  Canvas(Modifier.size(24.dp)) {
    val stroke = this.size.minDimension * 0.1f
    drawLine(
        color,
        Offset(this.size.width / 2, this.size.height * 0.2f),
        Offset(this.size.width / 2, this.size.height * 0.8f),
        stroke,
    )
    drawLine(
        color,
        Offset(this.size.width * 0.2f, this.size.height / 2),
        Offset(this.size.width * 0.8f, this.size.height / 2),
        stroke,
    )
  }
}

@Composable
private fun SearchGlyph(color: Color) {
  Canvas(Modifier.size(24.dp)) {
    val stroke = this.size.minDimension * 0.09f
    drawCircle(
        color,
        radius = this.size.minDimension * 0.33f,
        center = Offset(this.size.width * 0.45f, this.size.height * 0.45f),
        style = Stroke(stroke),
    )
    drawLine(
        color,
        Offset(this.size.width * 0.68f, this.size.height * 0.68f),
        Offset(this.size.width * 0.85f, this.size.height * 0.85f),
        stroke,
    )
  }
}

@Composable
private fun BackGlyph(color: Color) {
  Canvas(Modifier.size(24.dp)) {
    val stroke = this.size.minDimension * 0.09f
    drawLine(
        color,
        Offset(this.size.width * 0.38f, this.size.height * 0.22f),
        Offset(this.size.width * 0.64f, this.size.height * 0.5f),
        stroke,
    )
    drawLine(
        color,
        Offset(this.size.width * 0.64f, this.size.height * 0.5f),
        Offset(this.size.width * 0.38f, this.size.height * 0.78f),
        stroke,
    )
  }
}

@Composable
private fun CheckGlyph(color: Color) {
  Canvas(Modifier.size(44.dp)) {
    val stroke = this.size.minDimension * 0.1f
    drawLine(
        color,
        Offset(this.size.width * 0.2f, this.size.height * 0.52f),
        Offset(this.size.width * 0.42f, this.size.height * 0.72f),
        stroke,
    )
    drawLine(
        color,
        Offset(this.size.width * 0.42f, this.size.height * 0.72f),
        Offset(this.size.width * 0.8f, this.size.height * 0.28f),
        stroke,
    )
  }
}

/* --------------------------------------------------------------- text */

@Composable
private fun listLabelText(state: QuickAddState): String =
    if (state.query.isBlank()) stringResource(R.string.quickadd_recent)
    else "${state.people.size} " + stringResource(R.string.quickadd_result)

private fun firstWord(name: String): String = name.trim().split(' ').firstOrNull().orEmpty()

/** «سجّل ₪50 على خالد» → «₪50 على خالد»: the done step's title already carries the verb. */
internal fun withoutVerb(sentence: String): String = sentence.substringAfter(' ', sentence)

@Composable
private fun methodLabel(method: PaymentMethod): String =
    when (method) {
      PaymentMethod.CASH -> stringResource(R.string.quickadd_method_cash)
      PaymentMethod.BANK -> stringResource(R.string.quickadd_method_bank)
      else -> stringResource(R.string.quickadd_method_wallet)
    }

private val QUICK_METHODS = listOf(PaymentMethod.CASH, PaymentMethod.BANK, PaymentMethod.WALLET)

/** The add-new row's 1.5 dp dashed outline — `border: 1.5px dashed` in the board. */
private fun Modifier.dashedBorder(color: Color, radius: Dp, width: Dp = 1.5.dp) = drawBehind {
  val stroke = width.toPx()
  drawRoundRect(
      color = color,
      cornerRadius = CornerRadius(radius.toPx()),
      style =
          Stroke(
              width = stroke,
              pathEffect = PathEffect.dashPathEffect(floatArrayOf(stroke * 3f, stroke * 2f)),
          ),
  )
}

private val Ghost = Color(0xFF8FA89B)
