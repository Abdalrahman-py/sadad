package com.sadad.app.feature.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sadad.app.R
import com.sadad.app.SadadApplication
import com.sadad.app.ui.components.EmptyState
import com.sadad.app.ui.components.PendingBadge
import com.sadad.app.ui.components.SadadPageHeading
import com.sadad.app.ui.components.SadadSearchField
import com.sadad.app.ui.components.SadadSegments
import com.sadad.app.ui.components.formatCents

/** Stable handles the people tests find the list and its rows by. */
internal object PeopleTags {
  const val TITLE = "people:title"
  const val LIST = "contacts:list"
  const val SEARCH = "contacts:search"

  fun row(id: String) = "contacts:row:$id"
}

/** The People tab; the list and its model moved here unchanged from `feature/contacts`. */
@Composable
fun PeopleScreen(onOpenContact: (String) -> Unit) {
  ContactsScreen(onOpenContact = onOpenContact)
}

@Composable
fun ContactsScreen(
    modifier: Modifier = Modifier,
    onOpenContact: (String) -> Unit = {},
    onAddContact: () -> Unit = {},
) {
  val viewModel = contactsViewModel()
  ContactsScreen(viewModel, onOpenContact, modifier, onAddContact)
}

@Composable
internal fun ContactsScreen(
    viewModel: ContactsViewModel,
    onOpenContact: (String) -> Unit,
    modifier: Modifier = Modifier,
    onAddContact: () -> Unit = {},
) {
  val state by viewModel.state.collectAsState()
  Box(modifier.fillMaxSize()) {
    ContactsList(state, viewModel, onOpenContact)
  }
}

@Composable
private fun ContactsList(
    state: ContactsState,
    viewModel: ContactsViewModel,
    onOpenContact: (String) -> Unit,
) {
  Column(Modifier.fillMaxSize()) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      SadadPageHeading(
          title = stringResource(R.string.title_contacts),
          subtitle =
              pluralStringResource(
                  R.plurals.contacts_registered,
                  state.registered,
                  state.registered,
              ),
          titleModifier = Modifier.testTag(PeopleTags.TITLE),
      )
      SadadSearchField(
          value = state.queryInput,
          onValueChange = viewModel::onQueryChange,
          placeholder = stringResource(R.string.contacts_search),
          modifier = Modifier.fillMaxWidth().testTag(PeopleTags.SEARCH),
      )
      SadadSegments(
          options =
              listOf(
                  ContactSort.NAME to stringResource(R.string.contacts_sort_name),
                  ContactSort.HIGHEST_DEBT to stringResource(R.string.contacts_sort_debt),
                  ContactSort.NEWEST to stringResource(R.string.contacts_sort_newest),
              ),
          selected = state.sortChoice,
          onSelect = viewModel::onSortChange,
      )
    }
    // The bottom padding keeps the last row clear of the add button.
    LazyColumn(
        Modifier.fillMaxSize().testTag(PeopleTags.LIST),
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
      if (state.loaded && state.rows.isEmpty()) {
        item {
          EmptyState(
              title =
                  stringResource(
                      if (state.query.isBlank()) R.string.contacts_empty
                      else R.string.contacts_no_results
                  ),
              detail =
                  stringResource(
                      if (state.query.isBlank()) R.string.contacts_empty_detail
                      else R.string.contacts_no_results_detail
                  ),
          )
        }
      }
      items(state.rows, key = { it.id }) { row ->
        ContactRowItem(row, onOpenContact)
        HorizontalDivider()
      }
    }
  }
}

@Composable
private fun ContactRowItem(row: ContactRow, onOpenContact: (String) -> Unit) {
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .clickable { onOpenContact(row.id) }
              .padding(horizontal = 16.dp, vertical = 10.dp)
              .testTag(PeopleTags.row(row.id)),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = row.name, style = MaterialTheme.typography.titleMedium)
        if (row.pending) {
          Spacer(Modifier.width(6.dp))
          PendingBadge()
        }
      }
      Text(
          text = row.phone.ifBlank { stringResource(R.string.contacts_no_phone) },
          style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Text(text = formatCents(row.balance), style = MaterialTheme.typography.titleMedium)
  }
}

@Composable
private fun contactsViewModel(): ContactsViewModel {
  val app = LocalContext.current.applicationContext as SadadApplication
  return viewModel(
      factory =
          viewModelFactory {
            initializer { ContactsViewModel(app.graph, createSavedStateHandle()) }
          }
  )
}
