package com.sadad.app.feature.history

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Half-open local-day bounds: [PeriodBounds.from] inclusive, [PeriodBounds.to] exclusive. */
class PeriodTest {
  private val zone: ZoneId = ZoneId.of("Asia/Gaza")

  private fun at(text: String): Instant = Instant.parse(text)

  private fun startOf(date: LocalDate): Instant = date.atStartOfDay(zone).toInstant()

  @Test
  fun monthOnTheSeventeenthSpansTheFirstOfOctoberToTheFirstOfNovember() {
    val bounds = Period.Month(YearMonth.of(2026, 10)).bounds(zone)

    assertEquals(startOf(LocalDate.of(2026, 10, 1)), bounds.from)
    assertEquals(startOf(LocalDate.of(2026, 11, 1)), bounds.to)
  }

  @Test
  fun daySpansItsOwnMidnightToTheNextOne() {
    val bounds = Period.Day(LocalDate.of(2026, 10, 17)).bounds(zone)

    assertEquals(startOf(LocalDate.of(2026, 10, 17)), bounds.from)
    assertEquals(startOf(LocalDate.of(2026, 10, 18)), bounds.to)
  }

  @Test
  fun yearSpansJanuaryToJanuary() {
    val bounds = Period.Year(2026).bounds(zone)

    assertEquals(startOf(LocalDate.of(2026, 1, 1)), bounds.from)
    assertEquals(startOf(LocalDate.of(2027, 1, 1)), bounds.to)
  }

  @Test
  fun aCustomRangeEndsAtTheDayAfterItsLastDay() {
    val bounds =
        Period.Custom(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 17)).bounds(zone)

    assertEquals(startOf(LocalDate.of(2026, 10, 5)), bounds.from)
    assertEquals(startOf(LocalDate.of(2026, 10, 18)), bounds.to)
  }

  @Test
  fun allTimeHasNoBounds() {
    val bounds = Period.All.bounds(zone)

    assertEquals(null, bounds.from)
    assertEquals(null, bounds.to)
    assertTrue(at("1999-01-01T00:00:00Z") in bounds)
    assertTrue(at("2099-01-01T00:00:00Z") in bounds)
  }

  @Test
  fun theLastMinuteIsInsideAndTheNextMidnightIsOutside() {
    val bounds = Period.Month(YearMonth.of(2026, 10)).bounds(zone)

    // 23:59 on 31 October and 00:00 on 1 November, both local Asia/Gaza (UTC+2 after the change).
    assertTrue(startOf(LocalDate.of(2026, 10, 31)).plusSeconds(86_399) in bounds)
    assertFalse(startOf(LocalDate.of(2026, 11, 1)) in bounds)
  }

  @Test
  fun aDayBoundaryCountsTheFirstInstantOfTheDay() {
    val bounds = Period.Day(LocalDate.of(2026, 10, 17)).bounds(zone)

    assertTrue(startOf(LocalDate.of(2026, 10, 17)) in bounds)
    assertFalse(startOf(LocalDate.of(2026, 10, 17)).minusNanos(1) in bounds)
  }

  @Test
  fun aPeriodSurvivesBeingSavedAsText() {
    val periods =
        listOf(
            Period.All,
            Period.Day(LocalDate.of(2026, 10, 17)),
            Period.Month(YearMonth.of(2026, 10)),
            Period.Year(2026),
            Period.Custom(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 17)),
        )

    for (period in periods) {
      assertEquals(period, periodOf(period.encoded()))
    }
  }

  @Test
  fun unreadableSavedTextFallsBackToAllTime() {
    assertEquals(Period.All, periodOf(null))
    assertEquals(Period.All, periodOf("fortnight"))
  }
}
