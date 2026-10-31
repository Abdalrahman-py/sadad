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
import com.sadad.app.SadadApplication
import com.sadad.app.ui.components.BalanceTone
import com.sadad.app.ui.components.BalanceWord
import com.sadad.app.ui.components.KeypadKey
import com.sadad.app.ui.components.SadadButton
import com.sadad.app.ui.components.SadadButtonStyle
import com.sadad.app.ui.components.SadadKeypad
import com.sadad.app.ui.components.SadadTextField
import com.sadad.app.ui.components.VoiceDraftButton
import com.sadad.app.ui.components.balanceWord
import com.sadad.app.ui.components.parseCents
import com.sadad.app.ui.components.sadadPageBackground
import com.sadad.app.ui.theme.sadadPalette
import com.sadad.ledger.Cents
import com.sadad.ledger.ContactKind
import com.sadad.ledger.PaymentMethod
import java.time.Duration
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

/** Records for a saved person; creating a new person opens the full contact form first. */
@Composable
fun QuickAddScreen(
    contactId: String?,
    payment: Boolean,
    onClose: () -> Unit,
    onOpenContact: (String) -> Unit,
    onCreateContact: (String) -> Unit,
    viewModel: QuickAddViewModel = quickAddViewModel(contactId, payment),
) {
  val state by viewModel.state.collectAsState()
  val context = LocalContext.current
  val p = sadadPalette

  Box(Modifier.fillMaxSize().sadadPageBackground()) {
    when (state.step) {
      QuickAddStep.PICKER -> PickerStep(state, viewModel, onClose, onCreateContact)
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
            Text(stringResource(R.string.quickadd_anomaly_title), fontWeight = FontWeight.Bold)
          },
          text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Text(stringResource(R.string.quickadd_anomaly_body))
              Text(
                  stringResource(
                      R.string.quickadd_anomaly_sentence,
                      viewModel.sentence(context),
                  ),
                  fontWeight = FontWeight.Bold,
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
  val app = LocalContext.current.applicationContext as SadadApplication
  return viewModel(
      factory =
          viewModelFactory {
            initializer {
              QuickAddViewModel(app.graph, contactId, payment, createSavedStateHandle())
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
    onCreateContact: (String) -> Unit,
) {
  val p = sadadPalette
  Column(
      Modifier.fillMaxSize().imePadding().padding(horizontal = 18.dp),
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
          fontSize = 21.sp,
          fontWeight = FontWeight.Bold,
          color = p.text,
      )
      Spacer(Modifier.width(44.dp))
    }

    SearchField(
        query = state.query,
        onQuery = model::onQueryChange,
        onClear = model::clearQuery,
    )
    if (state.blocked) BlockedCard()

    Text(
        text = listLabelText(state),
        color = p.muted,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Bold,
    )

    LazyColumn(
        Modifier.fillMaxWidth().weight(1f).testTag(QuickAddTags.PEOPLE),
        contentPadding = PaddingValues(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
      if (state.query.isNotBlank()) {
        item {
          AddNewRow(name = state.query.trim(), onClick = { onCreateContact(state.query.trim()) })
        }
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
  LaunchedEffect(Unit) {
    focus.requestFocus()
    keyboard?.show()
  }
  Surface(
      color = p.card,
      shape = RoundedCornerShape(13.dp),
      border = BorderStroke(2.dp, if (p.isDark) p.accent else p.primary),
      modifier = Modifier.fillMaxWidth().height(52.dp),
  ) {
    Row(
        Modifier.fillMaxSize().padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      SearchGlyph(p.muted)
      BasicTextField(
          value = query,
          onValueChange = onQuery,
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
              if (query.isEmpty()) Text(hint, color = p.muted, fontSize = 15.sp)
              inner()
            }
          },
      )
      if (query.isNotEmpty()) {
        RoundIconButton(
            description = stringResource(R.string.quickadd_clear_search),
            size = 40,
            tag = QuickAddTags.CLEAR,
            onClick = onClear,
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
      shape = RoundedCornerShape(13.dp),
      modifier = Modifier.fillMaxWidth().testTag(QuickAddTags.BLOCKED),
  ) {
    Row(
        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text(
            stringResource(R.string.quickadd_blocked_title),
            fontWeight = FontWeight.Bold,
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
      shape = RoundedCornerShape(13.dp),
      border = BorderStroke(1.dp, p.line),
      modifier =
          Modifier.fillMaxWidth()
              .heightIn(min = 64.dp)
              .testTag(QuickAddTags.person(person.id))
              .clickable(onClick = onClick)
              .semantics { role = Role.Button },
  ) {
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Initials(person.name, supplier)
      Column(Modifier.weight(1f)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(person.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = p.text)
          if (supplier) SupplierBadge()
        }
        val detail = detailLine(person, Instant.now())
        if (detail.isNotEmpty()) {
          Text(detail, fontSize = 12.sp, color = p.muted)
        }
      }
      BalanceWord(person.net.value)
    }
  }
}

@Composable
private fun SupplierBadge() {
  val p = sadadPalette
  Surface(color = p.surface, shape = RoundedCornerShape(6.dp)) {
    Text(
        stringResource(R.string.quickadd_supplier_badge),
        color = p.muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
    )
  }
}

@Composable
private fun AddNewRow(name: String, onClick: () -> Unit) {
  val p = sadadPalette
  val link = if (p.isDark) p.accent else p.primary
  Surface(
      color = Color.Transparent,
      shape = RoundedCornerShape(13.dp),
      modifier =
          Modifier.fillMaxWidth()
              .heightIn(min = 64.dp)
              .dashedBorder(p.muted, 13.dp)
              .testTag(QuickAddTags.ADD_NEW)
              .clickable(onClick = onClick)
              .semantics { role = Role.Button },
  ) {
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
          Modifier.size(42.dp).clip(CircleShape).background(p.soft),
          contentAlignment = Alignment.Center,
      ) {
        PlusGlyph(link)
      }
      Text(
          stringResource(R.string.quickadd_new_result, name),
          fontWeight = FontWeight.Bold,
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
  Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, bottom = 20.dp)) {
    Row(
        Modifier.fillMaxWidth().height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      RoundIconButton(
          description = stringResource(R.string.quickadd_change_person),
          tag = QuickAddTags.BACK,
          // Opened from a person's page there is no one to change: back returns to that page.
          onClick = if (fromContact) onClose else model::backToPicker,
      ) {
        BackGlyph(p.text)
      }
      Initials(person?.name.orEmpty(), supplier == true)
      Column(Modifier.weight(1f)) {
        Text(
            person?.name.orEmpty(),
            fontWeight = FontWeight.Bold,
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
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
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
          Text("₪", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = p.text)
          Spacer(Modifier.width(8.dp))
          Text(
              text = amountLabel(state.amount),
              fontSize = 52.sp,
              fontWeight = FontWeight.Bold,
              color = if (state.amount.isEmpty()) Ghost else p.text,
              modifier = Modifier.testTag(QuickAddTags.AMOUNT),
          )
        }
      }
      val amount = parseCents(state.amount) ?: Cents.ZERO
      val over = state.payment && amount > state.overBalance
      Surface(
          color = if (over) p.creditSoft else Color.Transparent,
          shape = RoundedCornerShape(10.dp),
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
      VoiceDraftButton(onText = {}, modifier = Modifier.size(44.dp))
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
      Keypad(onKey = model::press, onDelete = model::delete)
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
      shape = RoundedCornerShape(13.dp),
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
      if (!state.restricted) {
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
      shape = RoundedCornerShape(10.dp),
      border = if (chosen) BorderStroke(1.5.dp, chosenInk) else null,
      modifier =
          modifier.height(44.dp).testTag(tag).clickable(onClick = onClick).semantics {
            role = Role.Button
          },
  ) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(
          label,
          color = if (chosen) chosenInk else p.muted,
          fontSize = 14.sp,
          fontWeight = FontWeight.Bold,
      )
    }
  }
}

/** The shared keypad, tagged per key as the quick add tests find them. */
@Composable
private fun Keypad(onKey: (String) -> Unit, onDelete: () -> Unit) {
  val dot = stringResource(R.string.quickadd_key_dot)
  val keys =
      listOf("1", "2", "3", "4", "5", "6", "7", "8", "9").map { KeypadKey.Char(it) } +
          listOf(
              KeypadKey.Char(".", dot),
              KeypadKey.Char("0"),
              KeypadKey.Delete(stringResource(R.string.quickadd_key_delete)),
          )
  SadadKeypad(
      keys = keys,
      onChar = onKey,
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
      shape = RoundedCornerShape(22.dp),
      border = BorderStroke(1.dp, p.line),
      modifier =
          Modifier.height(44.dp)
              .then(if (tag == null) Modifier else Modifier.testTag(tag))
              .clickable(onClick = onClick),
  ) {
    Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
      Text(
          label,
          color = if (p.isDark) p.accent else p.primary,
          fontSize = 13.sp,
          fontWeight = FontWeight.Bold,
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
        TextButton(
            enabled = picked != null,
            onClick = {
              if (picked != null) {
                onPick(Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate().toString())
              }
            },
        ) {
          Text(stringResource(R.string.quickadd_due_confirm))
        }
      },
      dismissButton = {
        Row {
          if (current != null) {
            TextButton(onClick = { onPick(null) }) {
              Text(stringResource(R.string.quickadd_due_clear))
            }
          }
          TextButton(onClick = onDismiss) { Text(stringResource(R.string.quickadd_cancel)) }
        }
      },
  ) {
    DatePicker(state = state)
  }
}

/** «البيان»: a line saying what the entry was for, kept with it in the ledger. */
@Composable
private fun NoteDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
  var text by remember { mutableStateOf(current) }
  AlertDialog(
      modifier = Modifier.testTag(QuickAddTags.NOTE_DIALOG),
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.quickadd_note), fontWeight = FontWeight.Bold) },
      text = {
        SadadTextField(
            value = text,
            onValueChange = { text = it.take(NOTE_MAX_CHARS) },
            placeholder = stringResource(R.string.quickadd_note_hint),
            modifier = Modifier.fillMaxWidth(),
        )
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
          .padding(start = 18.dp, end = 18.dp, top = 32.dp, bottom = 24.dp)
          .testTag(QuickAddTags.DONE),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(
        Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Box(
          Modifier.size(84.dp).clip(CircleShape).background(p.soft),
          contentAlignment = Alignment.Center,
      ) {
        CheckGlyph(if (p.isDark) p.accent else p.primary)
      }
      Text(title, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = p.text)
      Text(
          withoutVerb(done.sentence),
          fontSize = 15.sp,
          lineHeight = 24.sp,
          color = p.muted,
          textAlign = TextAlign.Center,
      )
      Surface(
          color = p.card,
          shape = RoundedCornerShape(13.dp),
          border = BorderStroke(1.dp, p.line),
      ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
              fontSize = 15.sp,
              fontWeight = FontWeight.Bold,
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
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      if (person != null) {
        SadadButton(
            text = stringResource(R.string.quickadd_open_contact, firstWord(person.name)),
            onClick = {
              model.leave(context)
              onOpenContact(person.id)
            },
            style = SadadButtonStyle.SOFT,
            modifier = Modifier.weight(1f).height(48.dp),
        )
      }
      SadadButton(
          text = stringResource(R.string.quickadd_home),
          onClick = {
            model.leave(context)
            onClose()
          },
          style = SadadButtonStyle.SOFT,
          modifier = Modifier.weight(1f).height(48.dp),
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
      shape = RoundedCornerShape(13.dp),
      color = fill,
      contentColor = ink,
      modifier =
          Modifier.fillMaxWidth().height(56.dp).testTag(QuickAddTags.SAVE).semantics {
            role = Role.Button
          },
  ) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ink)
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
private fun Initials(name: String, supplier: Boolean) {
  val p = sadadPalette
  val words = name.trim().split(' ').filter { it.isNotEmpty() }
  val text =
      when {
        words.size > 1 -> "${words[0].first()}${words[1].first()}"
        words.size == 1 -> words[0].take(2)
        else -> "؟"
      }
  val shape = if (supplier) RoundedCornerShape(12.dp) else CircleShape
  Box(Modifier.size(42.dp).clip(shape).background(p.soft), contentAlignment = Alignment.Center) {
    Text(
        text,
        color = if (p.isDark) p.accent else p.primary,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
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
  Canvas(Modifier.size(22.dp)) {
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
  Canvas(Modifier.size(22.dp)) {
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
        Offset(this.size.width * 0.62f, this.size.height * 0.22f),
        Offset(this.size.width * 0.36f, this.size.height * 0.5f),
        stroke,
    )
    drawLine(
        color,
        Offset(this.size.width * 0.36f, this.size.height * 0.5f),
        Offset(this.size.width * 0.62f, this.size.height * 0.78f),
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

/** «…342 · اليوم»: the phone's last three digits and when the store last dealt with them. */
private fun detailLine(person: QuickAddPerson, now: Instant): String {
  val tail = person.phone.filter { it.isDigit() }.takeLast(3)
  val activity = lastActivityText(person.lastActivity, now)
  return when {
    tail.isEmpty() -> activity
    activity.isEmpty() -> "…$tail"
    else -> "…$tail · $activity"
  }
}

/** Day-granularity, in the app's own words: the picker only needs "recently" or "a while ago". */
internal fun lastActivityText(activity: Instant?, now: Instant): String {
  if (activity == null) return ""
  val days = Duration.between(activity, now).toDays()
  return when {
    days <= 0 -> "اليوم"
    days == 1L -> "أمس"
    days < 7 -> "قبل $days أيام"
    days < 14 -> "قبل أسبوع"
    days < 30 -> "قبل ${days / 7} أسابيع"
    days < 60 -> "قبل شهر"
    else -> "قبل ${days / 30} أشهر"
  }
}

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
