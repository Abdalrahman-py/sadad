package com.sadad.app.feature.people

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.AppGraph
import com.sadad.app.ui.components.formatShekels
import com.sadad.ledger.Cents
import com.sadad.ledger.ContactKind
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerView
import java.text.Collator
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The chips: which of everyone the list shows. */
enum class PeopleFilter(val route: String?) {
  ALL(null),

  /** `net` above zero: they owe the store. */
  OWES("owes"),

  /** `net` below zero: the store holds their money, or owes a supplier. */
  CREDIT("credit"),

  /** They owe, and a receivable Debt of theirs is past its due date. */
  LATE("late"),
  SUPPLIERS("suppliers");

  companion object {
    /** The chip the route `People(filter)` preselects; anything else is الكل. */
    fun fromRoute(route: String?): PeopleFilter = entries.firstOrNull { it.route == route } ?: ALL
  }
}

/** The order the sort button cycles through: آخر حركة → الأعلى رصيدًا → الاسم → again. */
enum class PeopleSort {
  RECENT,
  BALANCE,
  NAME;

  val next: PeopleSort
    get() =
        when (this) {
          RECENT -> BALANCE
          BALANCE -> NAME
          NAME -> RECENT
        }
}

/** One person on the list. */
data class PeopleRow(
    val id: String,
    val name: String,
    val phone: String,
    val supplier: Boolean,
    /** Open receivable minus open payable, queued entries included. */
    val net: Cents,
    /** The newest entry about them, or when they were added. */
    val lastActiveAt: Instant,
    /** What the row's second line says: their last activity, or «متأخر 3 أيام». */
    val activity: String,
    /** Days past the oldest overdue receivable; null when they are not late. */
    val lateDays: Int?,
    /** «عميل دائم», «خضار وفواكه»: the category chosen when the person was added. */
    val category: String = "",
) {
  /** The last three digits the row shows after «…»; "" when the person has no number. */
  val phoneTail: String
    get() = if (phone.isBlank()) "" else phone.filter(Char::isDigit).takeLast(3)
}

data class PeopleState(
    val loaded: Boolean = false,
    val rows: List<PeopleRow> = emptyList(),
    /** Everyone in the store, whatever the chip and the search say. */
    val registered: Int = 0,
    /** How many people each chip shows under the current search. */
    val counts: Map<PeopleFilter, Int> = emptyMap(),
    /** «المجموع عليهم ₪ 1,455.00» for the chosen chip; null on الكل. */
    val totalLine: String? = null,
    /** The text the last projection filtered on. */
    val query: String = "",
    val filter: PeopleFilter = PeopleFilter.ALL,
    val sort: PeopleSort = PeopleSort.RECENT,
    /** What the search field shows: updated on the keystroke, before the projection catches up. */
    val queryInput: String = "",
    /** What the chips show, for the same reason. */
    val filterChoice: PeopleFilter = PeopleFilter.ALL,
    /** What the sort button shows, for the same reason. */
    val sortChoice: PeopleSort = PeopleSort.RECENT,
)

/** The chip the screen opens on: the last choice, else the route's `filter`, else الكل. */
internal fun initialPeopleFilter(saved: SavedStateHandle): PeopleFilter {
  val chosen =
      saved.get<String>(CHIP)?.let { name -> PeopleFilter.entries.firstOrNull { it.name == name } }
  return chosen ?: PeopleFilter.fromRoute(saved.get<String>(ROUTE_FILTER))
}

/**
 * Everyone the store holds, under one search, one chip and one order: the net per person, when they
 * last moved, how late they are, each chip's count under the search, and the chip's total line.
 *
 * The counts are the search's, not the chip's, so a chip never offers a number it cannot show. The
 * header's total is everyone: choosing عليهم does not change how many people the store has.
 */
fun peopleUiState(
    view: LedgerView,
    zone: ZoneId,
    today: LocalDate,
    query: String = "",
    filter: PeopleFilter = PeopleFilter.ALL,
    sort: PeopleSort = PeopleSort.RECENT,
): PeopleState {
  val nets = HashMap<String, Long>(view.contacts.size * 2)
  val debts = view.debts.groupBy { it.contactId }
  val payments = view.payments.groupBy { it.contactId }
  for (debt in view.debts) {
    val delta =
        if (debt.direction == Direction.RECEIVABLE) debt.remaining.value else -debt.remaining.value
    nets.merge(debt.contactId, delta, Long::plus)
  }

  val everyone = ArrayList<PeopleRow>(view.contacts.size)
  for (contact in view.contacts) {
    val own = debts[contact.id].orEmpty()
    var last = contact.createdAt
    var overdue = 0
    for (debt in own) {
      if (debt.createdAt.isAfter(last)) last = debt.createdAt
      if (debt.direction != Direction.RECEIVABLE || debt.remaining <= Cents.ZERO) continue
      val due = debt.dueDate?.let(::parseDue) ?: continue
      val days = ChronoUnit.DAYS.between(due, today).toInt()
      if (days > overdue) overdue = days
    }
    for (payment in payments[contact.id].orEmpty()) {
      if (payment.createdAt.isAfter(last)) last = payment.createdAt
    }
    val net = Cents(nets[contact.id] ?: 0L)
    val lateDays = if (net > Cents.ZERO && overdue > 0) overdue else null
    everyone +=
        PeopleRow(
            id = contact.id,
            name = contact.name,
            phone = contact.phone,
            supplier = contact.kind == ContactKind.SUPPLIER,
            net = net,
            lastActiveAt = last,
            activity =
                if (lateDays == null) activityText(last, today, zone) else lateText(lateDays),
            lateDays = lateDays,
            category = contact.category,
        )
  }

  val searched = everyone.filter { matches(it, query) }
  val counts = PeopleFilter.entries.associateWith { chip -> searched.count(chip::keeps) }
  val shown = searched.filter(filter::keeps)
  val ordered =
      when (sort) {
        PeopleSort.RECENT ->
            shown.sortedWith(
                compareByDescending<PeopleRow> { it.lastActiveAt }.then(compareByArabicName())
            )
        PeopleSort.BALANCE ->
            shown.sortedWith(
                compareByDescending<PeopleRow> { Math.abs(it.net.value) }
                    .then(compareByArabicName())
            )
        PeopleSort.NAME -> shown.sortedWith(compareByArabicName())
      }
  return PeopleState(
      loaded = true,
      rows = ordered,
      registered = view.contacts.size,
      counts = counts,
      totalLine = if (filter == PeopleFilter.ALL) null else totalLine(filter, shown),
      query = query,
      filter = filter,
      sort = sort,
  )
}

private fun PeopleFilter.keeps(row: PeopleRow): Boolean =
    when (this) {
      PeopleFilter.ALL -> true
      PeopleFilter.OWES -> row.net > Cents.ZERO
      PeopleFilter.CREDIT -> row.net < Cents.ZERO
      PeopleFilter.LATE -> row.lateDays != null
      PeopleFilter.SUPPLIERS -> row.supplier
    }

/** «المجموع عليهم ₪ 1,455.00»: what the chip's people add up to, either way round. */
private fun totalLine(filter: PeopleFilter, rows: List<PeopleRow>): String {
  val sum = rows.fold(0L) { total, row -> total + Math.abs(row.net.value) }
  val label =
      when (filter) {
        PeopleFilter.OWES -> "المجموع عليهم"
        PeopleFilter.CREDIT -> "المجموع لهم"
        PeopleFilter.LATE -> "المجموع المتأخر"
        PeopleFilter.SUPPLIERS -> "المجموع الموردون"
        PeopleFilter.ALL -> "المجموع"
      }
  return "$label ${formatShekels(Cents(sum))}"
}

/** The search: the normalised name contains it, or its digits are in the phone. */
private fun matches(row: PeopleRow, query: String): Boolean {
  val needle = searchText(query)
  if (needle.isEmpty()) return true
  if (searchText(row.name).contains(needle)) return true
  // A query with a letter is a name, never a number: «9123» finds a phone, «9123ح» does not.
  if (needle.any(Char::isLetter)) return false
  val digits = needle.filter(Char::isDigit)
  if (digits.isEmpty()) return false
  return searchText(row.phone).filter(Char::isDigit).contains(digits)
}

/**
 * The search's folding, its own copy because features do not import from one another: Arabic-Indic
 * and Persian digits become Latin, أ إ آ become ا, ة becomes ه and ى becomes ي, so «احمد» finds
 * «أحمد» and a number typed on an Arabic keyboard finds the stored phone.
 */
internal fun searchText(value: String): String =
    buildString(value.length) {
          for (character in value.trim()) {
            when {
              character in '\u0660'..'\u0669' -> append('0' + (character - '\u0660'))
              character in '\u06F0'..'\u06F9' -> append('0' + (character - '\u06F0'))
              character == 'أ' || character == 'إ' || character == 'آ' -> append('ا')
              character == 'ة' -> append('ه')
              character == 'ى' -> append('ي')
              else -> append(character)
            }
          }
        }
        .lowercase(Locale.ROOT)

/** «اليوم 10:24»، «أمس»، «قبل 5 أيام»، «قبل أسبوع»، «الشهر الماضي»: when they last moved. */
internal fun activityText(at: Instant, today: LocalDate, zone: ZoneId): String {
  val days = ChronoUnit.DAYS.between(at.atZone(zone).toLocalDate(), today).toInt()
  return when {
    days <= 0 -> "اليوم " + CLOCK.withZone(zone).format(at)
    days == 1 -> "أمس"
    days < 7 -> "قبل $days أيام"
    days < 14 -> "قبل أسبوع"
    days < 21 -> "قبل أسبوعين"
    days < 30 -> "قبل ${days / 7} أسابيع"
    days < 60 -> "الشهر الماضي"
    days < 365 -> "قبل ${days / 30} أشهر"
    else -> "قبل ${days / 365} سنة"
  }
}

/** «متأخر يومًا واحدًا»، «متأخر يومين»، «متأخر 3 أيام»، «متأخر 15 يومًا». */
internal fun lateText(days: Int): String =
    when (days) {
      1 -> "متأخر يومًا واحدًا"
      2 -> "متأخر يومين"
      in 3..10 -> "متأخر $days أيام"
      else -> "متأخر $days يومًا"
    }

private fun parseDue(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()

private fun compareByArabicName(): Comparator<PeopleRow> {
  val collator = Collator.getInstance(Locale.forLanguageTag("ar"))
  return Comparator { first, second -> collator.compare(first.name, second.name) }
}

/**
 * The list, projected on [Dispatchers.Default] from the one [LedgerView]. The search, the chip and
 * the order live in the [SavedStateHandle], so a recreated screen keeps them; [followDay] moves
 * «اليوم» and «متأخر 3 أيام» on at midnight while the tab stays open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeopleViewModel(
    private val graph: AppGraph,
    private val saved: SavedStateHandle,
    private val clock: Clock? = null,
) : ViewModel() {
  private data class Day(val zone: ZoneId, val today: LocalDate)

  private val query = MutableStateFlow(saved.get<String>(QUERY).orEmpty())
  private val filter = MutableStateFlow(initialPeopleFilter(saved))
  private val sort = MutableStateFlow(savedSort())
  private val day = MutableStateFlow(dayNow())
  private val mutable =
      MutableStateFlow(
          PeopleState(
              query = query.value,
              filter = filter.value,
              sort = sort.value,
              queryInput = query.value,
              filterChoice = filter.value,
              sortChoice = sort.value,
          )
      )
  val state: StateFlow<PeopleState> = mutable.asStateFlow()

  init {
    viewModelScope.launch {
      combine(view(), query, filter, sort, day) { view, text, chip, order, current ->
            if (view == null) {
              PeopleState(
                  query = text,
                  filter = chip,
                  sort = order,
                  queryInput = text,
                  filterChoice = chip,
                  sortChoice = order,
              )
            } else {
              peopleUiState(view, current.zone, current.today, text, chip, order)
                  .copy(queryInput = text, filterChoice = chip, sortChoice = order)
            }
          }
          .flowOn(Dispatchers.Default)
          .collect { mutable.value = it }
    }
  }

  fun onQueryChange(value: String) {
    saved[QUERY] = value
    query.value = value
    // The field reads this value; waiting for the projection on Dispatchers.Default would drop
    // characters typed faster than one round trip.
    mutable.update { it.copy(queryInput = value) }
  }

  fun onFilterChange(value: PeopleFilter) {
    saved[CHIP] = value.name
    filter.value = value
    mutable.update { it.copy(filterChoice = value) }
  }

  fun onSortChange(value: PeopleSort) {
    saved[SORT] = value.name
    sort.value = value
    mutable.update { it.copy(sortChoice = value) }
  }

  /** The sort button's tap: آخر حركة → الأعلى رصيدًا → الاسم. */
  fun cycleSort() = onSortChange(sort.value.next)

  /**
   * The screen cancels this wait when hidden and restarts it on return. It wakes at midnight
   * because «اليوم 10:24» becomes «أمس» there, and «متأخر 3 أيام» gains a day.
   */
  suspend fun followDay() {
    while (true) {
      day.value = dayNow()
      val current = clock ?: Clock.systemDefaultZone()
      val now = current.instant().atZone(current.zone)
      val midnight = now.toLocalDate().plusDays(1).atStartOfDay(current.zone)
      delay(Duration.between(now, midnight).toMillis().coerceAtLeast(1_000))
    }
  }

  private fun dayNow(): Day {
    val current = clock ?: Clock.systemDefaultZone()
    val now = current.instant().atZone(current.zone)
    return Day(now.zone, now.toLocalDate())
  }

  private fun savedSort(): PeopleSort =
      saved.get<String>(SORT)?.let { name -> PeopleSort.entries.firstOrNull { it.name == name } }
          ?: PeopleSort.RECENT

  private fun view(): Flow<LedgerView?> =
      graph.current.flatMapLatest { scope -> scope?.repository?.view ?: flowOf(null) }
}

private const val CHIP = "people:chip"

private const val QUERY = "people:query"

private const val SORT = "people:sort"

/** The `filter` argument of the `People(filter)` route, in the same handle. */
private const val ROUTE_FILTER = "filter"

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
