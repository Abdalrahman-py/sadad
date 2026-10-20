package com.sadad.app.feature.contacts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.AppGraph
import com.sadad.ledger.Cents
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerView
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

enum class ContactSort {
  NAME,
  HIGHEST_DEBT,
  NEWEST,
}

/**
 * One row of the contacts list: the open receivable balance and whether its rows are unconfirmed.
 */
data class ContactRow(
    val id: String,
    val name: String,
    val phone: String,
    val balance: Cents,
    val pending: Boolean,
    val createdAt: Instant,
)

data class ContactsState(
    val loaded: Boolean = false,
    val rows: List<ContactRow> = emptyList(),
    val registered: Int = 0,
    val query: String = "",
    val sort: ContactSort = ContactSort.NAME,
)

/**
 * The contacts list: every visible Contact with its open balance, filtered and ordered on
 * [Dispatchers.Default] from the one [LedgerView]. The search text and the order live in the
 * [SavedStateHandle], so a recreated screen keeps them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModel(
    private val graph: AppGraph,
    private val saved: SavedStateHandle,
) : ViewModel() {
  private val query = MutableStateFlow(saved.get<String>(QUERY).orEmpty())
  private val sort = MutableStateFlow(savedSort())
  private val mutable = MutableStateFlow(ContactsState(query = query.value, sort = sort.value))
  val state: StateFlow<ContactsState> = mutable.asStateFlow()

  init {
    viewModelScope.launch {
      combine(view(), query, sort) { view, text, order -> project(view, text, order) }
          .flowOn(Dispatchers.Default)
          .collect { mutable.value = it }
    }
  }

  fun onQueryChange(value: String) {
    saved[QUERY] = value
    query.value = value
  }

  fun onSortChange(value: ContactSort) {
    saved[SORT] = value.name
    sort.value = value
  }

  private fun savedSort(): ContactSort =
      saved.get<String>(SORT)?.let { name -> ContactSort.entries.firstOrNull { it.name == name } }
          ?: ContactSort.NAME

  private fun view(): Flow<LedgerView?> =
      graph.current.flatMapLatest { scope -> scope?.repository?.view ?: flowOf(null) }

  private fun project(
      view: LedgerView?,
      query: String,
      sort: ContactSort,
  ): ContactsState {
    if (view == null) {
      return ContactsState(query = query, sort = sort)
    }
    val debts = view.debts.groupBy { it.contactId }
    val pendingPayments = view.payments.filter { it.pending }.groupBy { it.contactId }
    val rows =
        view.contacts.map { contact ->
          val own = debts[contact.id].orEmpty()
          val balance =
              own.filter { it.direction == Direction.RECEIVABLE }
                  .fold(Cents.ZERO) { total, debt -> total + debt.remaining }
          val pending =
              contact.pending ||
                  own.any { it.pending || it.hasPendingPayments } ||
                  pendingPayments.containsKey(contact.id)
          ContactRow(contact.id, contact.name, contact.phone, balance, pending, contact.createdAt)
        }
    val needle = searchText(query)
    val filtered =
        if (needle.isEmpty()) rows
        else
            rows.filter { row ->
              searchText(row.name).contains(needle) ||
                  searchText(row.phone).filter(Char::isDigit).contains(needle)
            }
    val ordered =
        when (sort) {
          ContactSort.NAME -> filtered.sortedBy { it.name }
          ContactSort.HIGHEST_DEBT ->
              filtered.sortedWith(compareByDescending<ContactRow> { it.balance }.thenBy { it.name })
          ContactSort.NEWEST ->
              filtered.sortedWith(
                  compareByDescending<ContactRow> { it.createdAt }.thenBy { it.name }
              )
        }
    return ContactsState(
        loaded = true,
        rows = ordered,
        registered = view.contacts.size,
        query = query,
        sort = sort,
    )
  }

  private companion object {
    const val QUERY = "contacts:query"
    const val SORT = "contacts:sort"
  }
}

/** Arabic-Indic digits and the two Arabic decimal/grouping marks read as their Latin forms. */
internal fun searchText(value: String): String =
    buildString(value.length) {
          for (character in value.trim()) {
            when {
              character in '\u0660'..'\u0669' -> append('0' + (character - '\u0660'))
              character in '\u06F0'..'\u06F9' -> append('0' + (character - '\u06F0'))
              else -> append(character)
            }
          }
        }
        .lowercase(Locale.ROOT)
