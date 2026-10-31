package com.sadad.app.feature.people

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sadad.app.R
import com.sadad.app.SadadApplication
import com.sadad.app.ui.components.BalanceWord
import com.sadad.app.ui.components.SadadButton
import com.sadad.app.ui.components.SadadButtonStyle
import com.sadad.app.ui.components.sadadPageBackground
import com.sadad.app.ui.theme.sadadPalette

/** Stable handles the people tests find the screen's parts by. */
internal object PeopleTags {
  const val SCREEN = "people:screen"
  const val TITLE = "people:title"
  const val COUNT = "people:count"
  const val ADD = "people:add"
  const val FILTER = "people:filter"
  const val SEARCH = "people:search"
  const val LIST = "people:list"
  const val TOTAL = "people:total"

  fun row(id: String) = "people:row:$id"
}

/** The people tab: search, add and filter controls, and the contact list. */
@Composable
fun PeopleScreen(
    onOpenContact: (String) -> Unit = {},
    onAdd: () -> Unit = {},
    viewModel: PeopleViewModel = peopleViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(viewModel, lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.followDay() }
  }
  PeopleContent(
      state = state,
      onOpenContact = onOpenContact,
      onQueryChange = viewModel::onQueryChange,
      onFilter = viewModel::onFilterChange,
      onAdd = onAdd,
  )
}

@Composable
fun PeopleContent(
    state: PeopleState,
    onOpenContact: (String) -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onFilter: (PeopleFilter) -> Unit = {},
    onAdd: () -> Unit = {},
) {
  val p = sadadPalette
  Column(Modifier.fillMaxSize().sadadPageBackground().testTag(PeopleTags.SCREEN)) {
    Column(
        Modifier.padding(start = 18.dp, end = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      PeopleHeader(registered = state.registered)
      if (state.incomplete > 0)
          Text(
              stringResource(R.string.people_incomplete_count, state.incomplete),
              color = p.owes,
              fontSize = 13.sp,
          )
      PeopleSearch(value = state.queryInput, onValueChange = onQueryChange)
      PeopleActions(state, onAdd, onFilter)
    }
    val total = state.totalLine
    if (total != null) {
      Text(
          total,
          modifier =
              Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp).testTag(PeopleTags.TOTAL),
          fontSize = 13.sp,
          lineHeight = 20.sp,
          color = p.muted,
      )
    }
    PeopleList(state, onOpenContact)
  }
}

/* ------------------------------------------------------------------ header */

/** «الأشخاص» with the store's total. */
@Composable
private fun PeopleHeader(registered: Int) {
  val p = sadadPalette
  Row(
      Modifier.fillMaxWidth().heightIn(min = 64.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
          stringResource(R.string.people_title),
          modifier = Modifier.testTag(PeopleTags.TITLE).semantics { heading() },
          fontSize = 23.sp,
          lineHeight = 32.sp,
          fontWeight = FontWeight.Bold,
          color = p.text,
      )
      Text(
          registered.toString(),
          modifier = Modifier.padding(bottom = 6.dp).testTag(PeopleTags.COUNT),
          fontSize = 15.sp,
          lineHeight = 20.sp,
          fontWeight = FontWeight.Medium,
          color = p.muted,
      )
    }
  }
}

/** Add a contact and choose one of the existing filters, directly below search. */
@Composable
private fun PeopleActions(state: PeopleState, onAdd: () -> Unit, onFilter: (PeopleFilter) -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  Row(
      Modifier.fillMaxWidth().height(IntrinsicSize.Min),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    SadadButton(
        text = stringResource(R.string.people_add),
        onClick = onAdd,
        leading = painterResource(R.drawable.ic_person_add),
        modifier = Modifier.weight(1f).fillMaxHeight().testTag(PeopleTags.ADD),
    )
    Box(Modifier.weight(1f).fillMaxHeight()) {
      SadadButton(
          text = stringResource(R.string.people_filter_selected, filterLabel(state.filterChoice)),
          onClick = { expanded = true },
          style = SadadButtonStyle.SURFACE,
          trailing = painterResource(R.drawable.ic_chevron_down_wide),
          modifier = Modifier.fillMaxSize().testTag(PeopleTags.FILTER),
      )
      DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        PeopleFilter.entries.forEach { filter ->
          val chosen = filter == state.filterChoice
          DropdownMenuItem(
              text = { Text(filterLabel(filter)) },
              leadingIcon = { Text((state.counts[filter] ?: 0).toString()) },
              trailingIcon = {
                if (chosen) Icon(painterResource(R.drawable.ic_check), contentDescription = null)
              },
              modifier = Modifier.semantics { selected = chosen },
              onClick = {
                expanded = false
                onFilter(filter)
              },
          )
        }
      }
    }
  }
}

/* ------------------------------------------------------------------ search */

/** The board's 48dp field: card fill, 13dp corners, the glyph at the start. */
@Composable
private fun PeopleSearch(value: String, onValueChange: (String) -> Unit) {
  val p = sadadPalette
  val hint = stringResource(R.string.people_search)
  Row(
      Modifier.fillMaxWidth()
          .height(48.dp)
          .clip(RoundedCornerShape(13.dp))
          .background(p.card)
          .border(BorderStroke(1.dp, p.line), RoundedCornerShape(13.dp))
          .padding(horizontal = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(
        painterResource(R.drawable.ic_search),
        contentDescription = null,
        tint = p.muted,
        modifier = Modifier.size(21.dp),
    )
    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
      BasicTextField(
          value = value,
          onValueChange = onValueChange,
          singleLine = true,
          textStyle = TextStyle(fontSize = 15.sp, color = p.text),
          cursorBrush = SolidColor(p.accent),
          modifier =
              Modifier.fillMaxWidth().testTag(PeopleTags.SEARCH).semantics {
                contentDescription = hint
              },
          decorationBox = { field ->
            if (value.isEmpty()) {
              Text(hint, fontSize = 15.sp, color = p.muted, maxLines = 1)
            }
            field()
          },
      )
    }
  }
}

/* -------------------------------------------------------------------- list */

@Composable
private fun PeopleList(state: PeopleState, onOpenContact: (String) -> Unit) {
  LazyColumn(
      modifier = Modifier.fillMaxSize().testTag(PeopleTags.LIST),
      contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 20.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    if (state.loaded && state.rows.isEmpty()) item { NoResults() }
    items(state.rows, key = { it.id }) { row -> PeopleRowItem(row, onOpenContact) }
  }
}

/**
 * One person: avatar, name and the «مورد» badge, the phone tail with their last activity, balance.
 */
@Composable
private fun PeopleRowItem(row: PeopleRow, onOpenContact: (String) -> Unit) {
  val p = sadadPalette
  val subInk = if (row.lateDays != null) p.credit else p.muted
  Surface(
      onClick = { onOpenContact(row.id) },
      shape = RoundedCornerShape(13.dp),
      color = p.card,
      border = BorderStroke(1.dp, p.line),
      modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(PeopleTags.row(row.id)),
  ) {
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      PersonAvatar(row.name, row.supplier)
      Column(Modifier.weight(1f)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
              row.name,
              fontSize = 15.sp,
              lineHeight = 22.sp,
              fontWeight = FontWeight.Bold,
              color = p.text,
              maxLines = 1,
              overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
          )
          if (row.supplier) SupplierBadge()
        }
        // What the person is, then the number to reach them, then what happened last.
        val identity = listOf(row.category).filter { it.isNotBlank() }
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (identity.isNotEmpty()) {
            Text(
                identity.joinToString(" · "),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = p.muted,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (row.phone.isNotBlank())
                Text(" · ", fontSize = 12.sp, lineHeight = 18.sp, color = p.muted)
          }
          if (row.phone.isNotBlank()) {
            Text(
                row.phone,
                style =
                    LocalTextStyle.current.copy(
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = p.muted,
                        textDirection = TextDirection.Ltr,
                    ),
            )
          }
          if (identity.isEmpty() && row.phone.isBlank()) {
            Text(
                stringResource(R.string.people_no_details),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = p.muted,
            )
          }
        }
        Text(
            row.activity,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = subInk,
            fontWeight = if (row.lateDays != null) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
      }
      if (row.syncFailed) {
        Text(stringResource(R.string.contacts_saved_draft), color = p.owes, fontSize = 12.sp)
      } else {
        BalanceWord(row.net.value)
      }
    }
  }
}

/** The 42dp initials tile: a circle for a customer, the board's rounded square for a supplier. */
@Composable
private fun PersonAvatar(name: String, supplier: Boolean) {
  val p = sadadPalette
  Box(
      Modifier.size(42.dp)
          .clip(RoundedCornerShape(if (supplier) 12.dp else 21.dp))
          .background(p.soft)
          .clearAndSetSemantics {},
      contentAlignment = Alignment.Center,
  ) {
    Text(
        initialsOf(name),
        color = if (p.isDark) p.accent else p.primary,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
    )
  }
}

@Composable
private fun SupplierBadge() {
  val p = sadadPalette
  Box(
      Modifier.clip(RoundedCornerShape(6.dp)).background(p.surface).padding(horizontal = 6.dp),
  ) {
    Text(
        stringResource(R.string.people_supplier),
        color = p.muted,
        fontSize = 11.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Bold,
    )
  }
}

@Composable
private fun NoResults() {
  val p = sadadPalette
  Column(
      Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
        stringResource(R.string.people_no_results),
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        color = p.text,
        textAlign = TextAlign.Center,
    )
    Text(
        stringResource(R.string.people_no_results_detail),
        fontSize = 14.sp,
        lineHeight = 21.sp,
        color = p.muted,
        textAlign = TextAlign.Center,
    )
  }
}

/* -------------------------------------------------------------------- text */

/** The first letter of the first two words, as the board's «خ ي» tiles. */
internal fun initialsOf(name: String): String =
    name
        .trim()
        .split(' ')
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString("") { it.take(1) }
        .ifEmpty { "؟" }

@Composable
private fun filterLabel(filter: PeopleFilter): String =
    stringResource(
        when (filter) {
          PeopleFilter.ALL -> R.string.people_filter_all
          PeopleFilter.OWES -> R.string.people_filter_owes
          PeopleFilter.CREDIT -> R.string.people_filter_credit
          PeopleFilter.LATE -> R.string.people_filter_late
          PeopleFilter.SUPPLIERS -> R.string.people_filter_suppliers
        }
    )

@Composable
private fun peopleViewModel(): PeopleViewModel {
  val app = LocalContext.current.applicationContext as SadadApplication
  return viewModel(
      factory =
          viewModelFactory {
            initializer { PeopleViewModel(app.graph, createSavedStateHandle()) }
          }
  )
}
