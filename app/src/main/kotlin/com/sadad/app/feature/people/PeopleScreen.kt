package com.sadad.app.feature.people

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.sadad.app.requireGraph
import com.sadad.app.ui.components.BalanceWord
import com.sadad.app.ui.components.sadadPageBackground
import com.sadad.app.ui.theme.sadadPalette

/** Stable handles the people tests find the screen's parts by. */
internal object PeopleTags {
  const val SCREEN = "people:screen"
  const val ADD = "people:add"
  const val FILTER = "people:filter"
  const val FILTER_CHIP = "people:filter-chip"
  const val SEARCH = "people:search"
  const val LIST = "people:list"
  const val TOTAL = "people:total"

  fun row(id: String) = "people:row:$id"
}

/** The people tab: the search bar with its filter, the contact list and the add-person button. */
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
  val list = rememberLazyListState()
  Box(Modifier.fillMaxSize().sadadPageBackground().testTag(PeopleTags.SCREEN)) {
    Column(Modifier.fillMaxSize()) {
      Column(
          Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (state.incomplete > 0)
            Text(
                stringResource(R.string.people_incomplete_count, state.incomplete),
                color = p.owes,
                fontSize = 13.sp,
            )
        PeopleSearch(
            value = state.queryInput,
            onValueChange = onQueryChange,
            filter = state.filterChoice,
            counts = state.counts,
            onFilter = onFilter,
        )
        if (state.filterChoice != PeopleFilter.ALL) {
          ActiveFilterChip(state.filterChoice, onClear = { onFilter(PeopleFilter.ALL) })
        }
      }
      val total = state.totalLine
      if (total != null) {
        Text(
            total,
            modifier =
                Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp).testTag(PeopleTags.TOTAL),
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = p.muted,
        )
      }
      PeopleList(state, list, onOpenContact)
    }
    AddPersonFab(
        expanded = !list.canScrollBackward,
        onClick = onAdd,
        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
    )
  }
}

/* ------------------------------------------------------------------- add */

/**
 * The M3 extended FAB: tonal, at the end corner, so it never reads as the centre «+». It shrinks to
 * its icon once the list scrolls; TalkBack keeps the full label either way.
 */
@Composable
private fun AddPersonFab(expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val label = stringResource(R.string.people_add_person)
  ExtendedFloatingActionButton(
      text = { Text(label) },
      icon = { Icon(painterResource(R.drawable.ic_person_add), contentDescription = null) },
      expanded = expanded,
      onClick = onClick,
      modifier =
          modifier.testTag(PeopleTags.ADD).clearAndSetSemantics {
            contentDescription = label
            role = Role.Button
          },
  )
}

/* ---------------------------------------------------------------- filter */

/** Names the filter that is on, so the list is never narrowed without the user seeing why. */
@Composable
private fun ActiveFilterChip(filter: PeopleFilter, onClear: () -> Unit) {
  val name = filterLabel(filter)
  val clear = stringResource(R.string.people_filter_clear, name)
  InputChip(
      selected = true,
      onClick = onClear,
      label = { Text(name) },
      trailingIcon = {
        Icon(
            painterResource(R.drawable.ic_close),
            contentDescription = null,
            modifier = Modifier.size(InputChipDefaults.IconSize),
        )
      },
      // One button that says what it removes; the menu stays the only selectable list of filters.
      modifier =
          Modifier.testTag(PeopleTags.FILTER_CHIP).clearAndSetSemantics {
            contentDescription = clear
            role = Role.Button
            onClick {
              onClear()
              true
            }
          },
  )
}

/** The filter icon inside the search bar, with a dot while a filter other than «الكل» is on. */
@Composable
private fun FilterButton(
    filter: PeopleFilter,
    counts: Map<PeopleFilter, Int>,
    onFilter: (PeopleFilter) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  val label = stringResource(R.string.people_filter_selected, filterLabel(filter))
  Box {
    IconButton(
        onClick = { expanded = true },
        modifier = Modifier.testTag(PeopleTags.FILTER).semantics { contentDescription = label },
    ) {
      BadgedBox(badge = { if (filter != PeopleFilter.ALL) Badge() }) {
        Icon(painterResource(R.drawable.ic_filter), contentDescription = null)
      }
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      PeopleFilter.entries.forEach { option ->
        val chosen = option == filter
        DropdownMenuItem(
            text = { Text(filterLabel(option)) },
            leadingIcon = { Text((counts[option] ?: 0).toString()) },
            trailingIcon = {
              if (chosen) Icon(painterResource(R.drawable.ic_check), contentDescription = null)
            },
            modifier = Modifier.semantics { selected = chosen },
            onClick = {
              expanded = false
              onFilter(option)
            },
        )
      }
    }
  }
}

/* ------------------------------------------------------------------ search */

/** The M3 search bar, always shown: search glyph at the start, the filter icon at the end. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeopleSearch(
    value: String,
    onValueChange: (String) -> Unit,
    filter: PeopleFilter,
    counts: Map<PeopleFilter, Int>,
    onFilter: (PeopleFilter) -> Unit,
) {
  val hint = stringResource(R.string.people_search)
  // The field owns its text: a value that comes back through the view model's flow arrives late,
  // and the cursor jumps while the owner types. Only a reset to empty flows back in.
  var text by rememberSaveable { mutableStateOf(value) }
  LaunchedEffect(value) { if (value.isEmpty()) text = "" }
  SearchBarDefaults.InputField(
      query = text,
      onQueryChange = {
        text = it
        onValueChange(it)
      },
      onSearch = {},
      expanded = false,
      onExpandedChange = {},
      placeholder = { Text(hint, maxLines = 1) },
      leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
      trailingIcon = { FilterButton(filter, counts, onFilter) },
      colors =
          SearchBarDefaults.inputFieldColors(
              focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
              unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
          ),
      modifier =
          Modifier.fillMaxWidth().testTag(PeopleTags.SEARCH).semantics {
            contentDescription = hint
          },
  )
}

/* -------------------------------------------------------------------- list */

@Composable
private fun PeopleList(state: PeopleState, list: LazyListState, onOpenContact: (String) -> Unit) {
  // A keyed list keeps the first visible person in place when the rows change, so clearing a search
  // would leave everyone above them out of view; a new search, chip or order starts at the top.
  LaunchedEffect(state.query, state.filter, state.sort) { list.scrollToItem(0) }
  LazyColumn(
      state = list,
      modifier = Modifier.fillMaxSize().testTag(PeopleTags.LIST),
      // The bottom clears the add button, so the last balance is never under it.
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
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
      shape = RoundedCornerShape(12.dp),
      color = p.card,
      border = BorderStroke(1.dp, p.line),
      modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(PeopleTags.row(row.id)),
  ) {
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      PersonAvatar(row.initials, row.supplier)
      Column(Modifier.weight(1f)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
              row.name,
              style = MaterialTheme.typography.titleSmall,
              color = p.text,
              maxLines = 1,
              overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
          )
          if (row.supplier) SupplierBadge()
        }
        // What the person is, then the number to reach them, then what happened last.
        val identity = row.identityLine
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (identity.isNotEmpty()) {
            Text(
                identity,
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
            fontWeight = if (row.lateDays != null) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
      }
      if (row.syncFailed) {
        Text(stringResource(R.string.contacts_saved_draft), color = p.owes, fontSize = 12.sp)
      } else {
        BalanceWord(row.balance)
      }
    }
  }
}

/** The 42dp initials tile: a circle for a customer, the board's rounded square for a supplier. */
@Composable
private fun PersonAvatar(initials: String, supplier: Boolean) {
  val p = sadadPalette
  Box(
      Modifier.size(40.dp)
          .clip(RoundedCornerShape(if (supplier) 12.dp else 20.dp))
          .background(p.soft)
          .clearAndSetSemantics {},
      contentAlignment = Alignment.Center,
  ) {
    Text(
        initials,
        color = if (p.isDark) p.accent else p.primary,
        style = MaterialTheme.typography.titleSmall,
    )
  }
}

@Composable
private fun SupplierBadge() {
  val p = sadadPalette
  Box(
      Modifier.clip(RoundedCornerShape(8.dp)).background(p.surface).padding(horizontal = 8.dp),
  ) {
    Text(
        stringResource(R.string.people_supplier),
        color = p.muted,
        style = MaterialTheme.typography.bodySmall,
    )
  }
}

@Composable
private fun NoResults() {
  val p = sadadPalette
  Column(
      Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
        stringResource(R.string.people_no_results),
        style = MaterialTheme.typography.titleMedium,
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
  val graph = requireGraph()
  return viewModel(
      factory =
          viewModelFactory {
            initializer { PeopleViewModel(graph.peopleSource(), createSavedStateHandle()) }
          }
  )
}
