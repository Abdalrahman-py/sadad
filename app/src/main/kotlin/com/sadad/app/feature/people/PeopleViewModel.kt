package com.sadad.app.feature.people

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.data.db.ContactSummaryEntity
import com.sadad.app.data.ledger.ContactRecords
import com.sadad.app.feature.quickadd.PersonSearch
import com.sadad.app.ui.components.BalanceWordText
import com.sadad.app.ui.components.balanceWord
import com.sadad.app.ui.components.formatShekels
import com.sadad.ledger.Cents
import com.sadad.ledger.ContactKind
import com.sadad.ledger.ContactView
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
import kotlinx.coroutines.flow.map
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
    val syncFailed: Boolean = false,
) {
  /** The folded name and the phone's digits, folded once per row instead of per keystroke. */
  internal val nameKey: String = PersonSearch.normalize(name)
  internal val phoneDigits: String = PersonSearch.normalize(phone).filter(Char::isDigit)

  /** The last three digits the row shows after «…»; "" when the person has no number. */
  val phoneTail: String
    get() = if (phone.isBlank()) "" else phone.filter(Char::isDigit).takeLast(3)

  /** Ready-made row strings, built where the row is built and read by the row unchanged. */
  val initials: String = initialsOf(name)

  val balance: BalanceWordText = balanceWord(net.value)

  /** The category as the row's identity line shows it; "" when there is none. */
  val identityLine: String = if (category.isBlank()) "" else category
}

/** The first letter of the first two words, as the board's «خ ي» tiles. */
internal fun initialsOf(name: String): String =
    name
        .trim()
        .split(' ')
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString("") { it.take(1) }
        .ifEmpty { "؟" }

data class PeopleState(
    val loaded: Boolean = false,
    val rows: List<PeopleRow> = emptyList(),
    /** Everyone in the store, whatever the chip and the search say. */
    val registered: Int = 0,
    val incomplete: Int = 0,
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
): PeopleState =
    peopleUiState(
        sortRows(peopleRows(view, zone, today), sort),
        view.contacts.size,
        query,
        filter,
        sort,
    )

/**
 * Everyone in the stored summary, under the same search, chip and order as [peopleUiState] from the
 * view: it is the list People shows once the summary is filled.
 */
fun peopleUiState(
    summaries: List<ContactSummaryEntity>,
    drafts: List<ContactView>,
    zone: ZoneId,
    today: LocalDate,
    query: String = "",
    filter: PeopleFilter = PeopleFilter.ALL,
    sort: PeopleSort = PeopleSort.RECENT,
): PeopleState =
    peopleUiState(
        sortRows(summaryRows(summaries, drafts, zone, today), sort),
        summaries.size,
        query,
        filter,
        sort,
    )

/**
 * Every person's row from the stored summary: the same figures `peopleRows` reads off the view. A
 * rejected `contact.create` the summary does not know stays listed, with `syncFailed` and no net —
 * the directory's row for it under [ContactRecords] had no debts either.
 */
internal fun summaryRows(
    summaries: List<ContactSummaryEntity>,
    drafts: List<ContactView>,
    zone: ZoneId,
    today: LocalDate,
): List<PeopleRow> {
  val rows = summaries.map { row ->
    val net = Cents(row.net)
    val last = maxOf(row.createdAt, row.lastEntryAt ?: row.createdAt)
    val lateDays =
        row.earliestOpenDue
            ?.let(::parseDue)
            ?.let { ChronoUnit.DAYS.between(it, today).toInt() }
            ?.takeIf { net > Cents.ZERO && it > 0 }
    PeopleRow(
        id = row.contactId,
        name = row.name,
        phone = row.phone,
        supplier = row.kind == ContactKind.SUPPLIER,
        net = net,
        lastActiveAt = last,
        activity = if (lateDays == null) activityText(last, today, zone) else lateText(lateDays),
        lateDays = lateDays,
        category = row.category,
    )
  }

  val failed = drafts.map { draft ->
    PeopleRow(
        id = draft.id,
        name = draft.name,
        phone = draft.phone,
        supplier = draft.kind == ContactKind.SUPPLIER,
        net = Cents.ZERO,
        lastActiveAt = draft.createdAt,
        activity = activityText(draft.createdAt, today, zone),
        lateDays = null,
        category = draft.category,
        syncFailed = true,
    )
  }

  return rows + failed
}

/**
 * Every person's row: the one pass over the whole ledger. It depends only on the view and the day,
 * so the screen runs it when those change, never per keystroke.
 */
internal fun peopleRows(view: LedgerView, zone: ZoneId, today: LocalDate): List<PeopleRow> {
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

  return everyone
}

/** [rows] in [sort]'s order; run when the rows or the order change, so a search only filters. */
internal fun sortRows(rows: List<PeopleRow>, sort: PeopleSort): List<PeopleRow> =
    when (sort) {
      PeopleSort.RECENT ->
          rows.sortedWith(
              compareByDescending<PeopleRow> { it.lastActiveAt }.then(compareByArabicName())
          )
      PeopleSort.BALANCE ->
          rows.sortedWith(
              compareByDescending<PeopleRow> { Math.abs(it.net.value) }.then(compareByArabicName())
          )
      PeopleSort.NAME -> rows.sortedWith(compareByArabicName())
    }

/** The per-keystroke part: [sorted] (already in [sort]'s order) narrowed by the search and chip. */
internal fun peopleUiState(
    sorted: List<PeopleRow>,
    registered: Int,
    query: String,
    filter: PeopleFilter,
    sort: PeopleSort,
): PeopleState {
  val needle = PersonSearch.normalize(query)
  val searched = if (needle.isEmpty()) sorted else sorted.filter { matches(it, needle) }
  val counts = PeopleFilter.entries.associateWith { chip -> searched.count(chip::keeps) }
  val shown = searched.filter(filter::keeps)
  return PeopleState(
      loaded = true,
      rows = shown,
      registered = registered,
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

/** The search ([needle] already folded): the name contains it, or its digits are in the phone. */
private fun matches(row: PeopleRow, needle: String): Boolean {
  if (row.nameKey.contains(needle)) return true
  // A query with a letter is a name, never a number: «9123» finds a phone, «9123ح» does not.
  if (needle.any(Char::isLetter)) return false
  val digits = needle.filter(Char::isDigit)
  if (digits.isEmpty()) return false
  return row.phoneDigits.contains(digits)
}

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
 * The list, projected on [Dispatchers.Default] from the stored summary once it is filled, and from
 * the one [LedgerView] while the summary is still filling. The search, the chip and the order live
 * in the [SavedStateHandle], so a recreated screen keeps them; [followDay] moves «اليوم» and «متأخر
 * 3 أيام» on at midnight while the tab stays open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeopleViewModel(
    private val source: PeopleSource,
    private val saved: SavedStateHandle,
    private val clock: Clock? = null,
) : ViewModel() {

  /**
   * The screen tests build the model against the open store's records; it never sees a filled
   * summary, so this is the filling window's list, for the life of this constructor.
   */
  constructor(
      records: Flow<ContactRecords?>,
      saved: SavedStateHandle,
      clock: Clock? = null,
  ) : this(fillSource(records), saved, clock)

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

  /** Everyone's rows, rebuilt only when the ledger or the day changes. */
  private class Rows(val all: List<PeopleRow>, val registered: Int, val incomplete: Int)

  private class Sorted(val rows: Rows, val order: PeopleSort, val list: List<PeopleRow>)

  init {
    val rows =
        combine(source.summaryReady, day) { ready, current -> ready to current }
            .flatMapLatest { (ready, current) ->
              if (ready) {
                combine(source.summaries, source.failedDrafts) { summaries, drafts ->
                  summaries?.let {
                    Rows(
                        summaryRows(it, drafts, current.zone, current.today),
                        it.size,
                        drafts.size,
                    )
                  }
                }
              } else {
                source.records.map { records ->
                  val view = records?.directory ?: return@map null
                  val failedIds = records.failed.mapTo(HashSet()) { it.contact.id }
                  Rows(
                      peopleRows(view, current.zone, current.today).map {
                        if (it.id in failedIds) it.copy(syncFailed = true) else it
                      },
                      records.ledger.contacts.size,
                      records.failed.size,
                  )
                }
              }
            }
            .flowOn(Dispatchers.Default)
    // Sorting is the costly comparison (an Arabic collator), so it runs per order, not per letter.
    val sorted =
        combine(rows, sort) { rows, order ->
              rows?.let { Sorted(it, order, sortRows(it.all, order)) }
            }
            .flowOn(Dispatchers.Default)
    viewModelScope.launch {
      combine(sorted, query, filter) { sorted, text, chip ->
            if (sorted == null) {
              PeopleState(query = text, filter = chip, sort = sort.value)
            } else {
              peopleUiState(sorted.list, sorted.rows.registered, text, chip, sorted.order)
                  .copy(incomplete = sorted.rows.incomplete)
            }
          }
          .flowOn(Dispatchers.Default)
          .collect { next ->
            // What the field, the chips and the sort button show is the latest tap's, never an
            // older projection's: a stale query here moved the cursor while the owner typed.
            mutable.update {
              next.copy(
                  queryInput = it.queryInput,
                  filterChoice = it.filterChoice,
                  sortChoice = it.sortChoice,
              )
            }
          }
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
}

private const val CHIP = "people:chip"

private const val QUERY = "people:query"

private const val SORT = "people:sort"

/** The `filter` argument of the `People(filter)` route, in the same handle. */
private const val ROUTE_FILTER = "filter"

/** A source whose summary never fills, so the records constructor reads today's list for ever. */
private fun fillSource(records: Flow<ContactRecords?>): PeopleSource =
    object : PeopleSource {
      override val summaries = flowOf<List<ContactSummaryEntity>?>(null)
      override val summaryReady = flowOf(false)
      override val failedDrafts = flowOf<List<ContactView>>(emptyList())
      override val records = records
    }

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
