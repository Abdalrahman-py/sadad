package com.sadad.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.data.ledger.OutboxState
import com.sadad.app.data.sync.SyncStatus
import com.sadad.ledger.Cents
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** One person on «تحتاج متابعة»: what they owe, and how late — `null` means due today. */
data class HomeFollowUp(
    val contactId: String,
    val name: String,
    val lateDays: Int?,
    val amount: Cents,
)

/** The offline banner: how many entries wait, and how long the store may keep recording. */
data class HomeOffline(val queued: Int, val leaseEndsAt: Instant?)

data class HomeUiState(
    val storeName: String = "",
    val month: YearMonth,
    /** «لك عند الناس»: the sum of every positive net. */
    val owedToStore: Cents = Cents.ZERO,
    /** «عليك للناس»: the sum of every negative net, as a positive amount. */
    val storeOwes: Cents = Cents.ZERO,
    val monthDebts: Cents = Cents.ZERO,
    val monthPayments: Cents = Cents.ZERO,
    val monthPaymentsCount: Int = 0,
    /** Collection ÷ month debts, capped at 100. */
    val collectionPercent: Int = 0,
    val followUps: List<HomeFollowUp> = emptyList(),
    /** Everyone who needs chasing, not only the rows drawn. */
    val followUpCount: Int = 0,
    val empty: Boolean = false,
    val offline: HomeOffline? = null,
    /** True while the open store's sync pass is running: the pull to refresh indicator. */
    val refreshing: Boolean = false,
    /** False only before the summary table is ready, when the zeros above mean nothing yet. */
    val loaded: Boolean = true,
)

/** At most this many rows on «تحتاج متابعة»; the rest are behind «عرض الكل». */
const val FOLLOW_UP_ROWS: Int = 5

/** Most days late first, then the largest amount, then the name. */
private val followUpOrder: Comparator<HomeFollowUp> =
    compareByDescending<HomeFollowUp> { it.lateDays ?: 0 }
        .thenByDescending { it.amount.value }
        .thenBy { it.name }

/** Collection ÷ month debts, capped at 100; no debts means no share. */
private fun collectionPercent(monthDebts: Cents, monthPayments: Cents): Int =
    if (monthDebts.value > 0L) {
          ((monthPayments.value * 100L + monthDebts.value / 2) / monthDebts.value).coerceAtMost(
              100L
          )
        } else {
          0L
        }
        .toInt()

/**
 * Follows the open store: its stored summary, the queue, the connection, and the store's name. Room
 * is the only ledger (ticket 168): until the summary fill ends, the screen shows its loading state,
 * and once it is ready every figure comes from Room. The rest only changes the banner. Off screen
 * it stops.
 */
class HomeViewModel
internal constructor(
    private val source: HomeSource,
    private val clock: Clock? = null,
) : ViewModel() {
  private data class Period(val month: YearMonth, val zone: ZoneId, val today: LocalDate)

  private fun clockNow(): Clock = clock ?: Clock.systemDefaultZone()

  private fun periodNow(): Period {
    val current = clockNow()
    val now = current.instant().atZone(current.zone)
    return Period(YearMonth.from(now), now.zone, now.toLocalDate())
  }

  private val period = MutableStateFlow(periodNow())

  /**
   * The screen cancels this wait when hidden and restarts it on return. It wakes at midnight, not
   * only at the month boundary, because «متأخر 3 أيام» gains a day there.
   */
  suspend fun followMonth() {
    while (true) {
      period.value = periodNow()
      val current = clockNow()
      val now = current.instant().atZone(current.zone)
      val midnight = now.toLocalDate().plusDays(1).atStartOfDay(current.zone)
      delay(Duration.between(now, midnight).toMillis().coerceAtLeast(1_000))
    }
  }

  /** The pull to refresh on Home starts one pass for the open store; offline it ends at once. */
  fun refresh() = source.refresh()

  // The Room queries answer once the summary fill ends (summaryReady); a sync status flip never
  // reaches the heavy part.
  private val totals: Flow<HomeUiState> =
      combine(source.current, period) { scope, period -> scope to period }
          .flatMapLatest { (scope, period) ->
            if (scope == null) {
              flowOf(HomeUiState(month = period.month, loaded = false))
            } else {
              source.summaryReady(scope).flatMapLatest { ready ->
                if (ready) {
                  source.roomData(scope, period.month, period.zone, period.today).map { data ->
                    roomState(data, period)
                  }
                } else {
                  // The ledger view this fell back to is gone (ticket 168): loading it is.
                  flowOf(HomeUiState(month = period.month, loaded = false))
                }
              }
            }
          }

  private fun roomState(data: HomeRoomData, period: Period): HomeUiState {
    val followUps =
        data.followUpCandidates
            .map { candidate ->
              val due = LocalDate.parse(candidate.earliestOpenDue)
              val days = ChronoUnit.DAYS.between(due, period.today).toInt()
              HomeFollowUp(
                  candidate.contactId,
                  candidate.name,
                  if (days > 0) days else null,
                  candidate.net,
              )
            }
            .sortedWith(followUpOrder)
    return HomeUiState(
        month = period.month,
        owedToStore = data.owed,
        storeOwes = data.owes,
        monthDebts = data.monthDebts,
        monthPayments = data.monthPayments,
        monthPaymentsCount = data.monthPaymentsCount,
        collectionPercent = collectionPercent(data.monthDebts, data.monthPayments),
        followUps = followUps.take(FOLLOW_UP_ROWS),
        followUpCount = followUps.size,
        empty = data.empty,
    )
  }

  private data class Banner(
      val storeName: String,
      val offline: HomeOffline?,
      val refreshing: Boolean,
  )

  private val banner: Flow<Banner> =
      combine(source.current, source.accounts) { scope, accounts -> scope to accounts }
          .flatMapLatest { (scope, accounts) ->
            val name =
                accounts
                    .firstOrNull { it.storeUserId == scope?.account?.storeUserId }
                    ?.storeName
                    .orEmpty()
            if (scope == null) {
              flowOf(Banner(name, null, false))
            } else {
              combine(scope.outbox.rows, source.syncStatus(scope.account.storeUserId)) {
                  rows,
                  status ->
                val queued = rows.count { it.state == OutboxState.QUEUED }
                val offline = status == SyncStatus.Offline || !scope.isOnline()
                val leaseEndsAt = source.offlineUntil(scope.account)?.let(Instant::ofEpochMilli)
                Banner(
                    storeName = name,
                    offline = if (offline && queued > 0) HomeOffline(queued, leaseEndsAt) else null,
                    // The pull spinner is the running pass itself; offline never enters it.
                    refreshing = status == SyncStatus.Running,
                )
              }
            }
          }

  val state: StateFlow<HomeUiState> =
      combine(totals, banner) { totals, banner ->
            totals.copy(
                storeName = banner.storeName,
                offline = banner.offline,
                refreshing = banner.refreshing,
            )
          }
          .flowOn(Dispatchers.Default)
          .stateIn(
              viewModelScope,
              SharingStarted.WhileSubscribed(5_000),
              HomeUiState(month = period.value.month, loaded = false),
          )
}
