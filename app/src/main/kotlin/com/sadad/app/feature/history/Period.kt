package com.sadad.app.feature.history

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The history's chosen period. Bounds are local-zone day starts; [PeriodBounds.to] is exclusive.
 */
sealed interface Period {
  data class Day(val date: LocalDate) : Period

  data class Month(val month: YearMonth) : Period

  data class Year(val year: Int) : Period

  data class Custom(val from: LocalDate, val to: LocalDate) : Period

  data object All : Period
}

/** The four presets the filter offers; [Period.Custom] comes from the date range picker. */
enum class PeriodPreset {
  DAY,
  MONTH,
  YEAR,
  ALL,
}

/** A half-open instant range: [from] inclusive, [to] exclusive. Null means unbounded. */
data class PeriodBounds(val from: Instant?, val to: Instant?) {
  operator fun contains(at: Instant): Boolean =
      (from == null || !at.isBefore(from)) && (to == null || at.isBefore(to))
}

fun Period.bounds(zone: ZoneId): PeriodBounds =
    when (this) {
      is Period.Day ->
          PeriodBounds(
              date.atStartOfDay(zone).toInstant(),
              date.plusDays(1).atStartOfDay(zone).toInstant(),
          )
      is Period.Month ->
          PeriodBounds(
              month.atDay(1).atStartOfDay(zone).toInstant(),
              month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant(),
          )
      is Period.Year ->
          PeriodBounds(
              LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant(),
              LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant(),
          )
      is Period.Custom ->
          PeriodBounds(
              from.atStartOfDay(zone).toInstant(),
              to.plusDays(1).atStartOfDay(zone).toInstant(),
          )
      Period.All -> PeriodBounds(null, null)
    }

/** The preset this period came from, or null for [Period.Custom]. */
val Period.preset: PeriodPreset?
  get() =
      when (this) {
        is Period.Day -> PeriodPreset.DAY
        is Period.Month -> PeriodPreset.MONTH
        is Period.Year -> PeriodPreset.YEAR
        Period.All -> PeriodPreset.ALL
        is Period.Custom -> null
      }

/** [today] is the local date the presets count from. */
fun periodOf(preset: PeriodPreset, today: LocalDate): Period =
    when (preset) {
      PeriodPreset.DAY -> Period.Day(today)
      PeriodPreset.MONTH -> Period.Month(YearMonth.from(today))
      PeriodPreset.YEAR -> Period.Year(today.year)
      PeriodPreset.ALL -> Period.All
    }

/** The text a [androidx.lifecycle.SavedStateHandle] keeps; [periodOf] reads it back. */
fun Period.encoded(): String =
    when (this) {
      is Period.Day -> "day:$date"
      is Period.Month -> "month:$month"
      is Period.Year -> "year:$year"
      is Period.Custom -> "custom:$from:$to"
      Period.All -> "all"
    }

fun periodOf(text: String?): Period {
  if (text.isNullOrBlank()) return Period.All
  val parts = text.split(':')
  return try {
    when (parts[0]) {
      "day" -> Period.Day(LocalDate.parse(parts[1]))
      "month" -> Period.Month(YearMonth.parse(parts[1]))
      "year" -> Period.Year(parts[1].toInt())
      "custom" -> Period.Custom(LocalDate.parse(parts[1]), LocalDate.parse(parts[2]))
      else -> Period.All
    }
  } catch (_: RuntimeException) {
    // A saved period this build cannot read shows everything instead of an empty list.
    Period.All
  }
}
