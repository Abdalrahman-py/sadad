package com.sadad.app.feature.settings

import com.sadad.app.feature.history.Period
import com.sadad.app.feature.history.PeriodPreset
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThemePreferenceTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test
  fun aNewFileHoldsTheSystemThemeAndAMonthlyExportPeriod() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val preference =
          ThemePreference.create(scope, File(temporary.root, "settings.preferences_pb"))

      assertEquals(ThemeChoice.SYSTEM, preference.theme.first())
      assertEquals(ExportPeriod.Preset(PeriodPreset.MONTH), preference.exportPeriod.first())
    } finally {
      scope.coroutineContext[Job]?.cancelAndJoin()
    }
  }

  @Test
  fun theChoicesComeBackFromTheFileAfterTheStoreIsReopened() = runBlocking {
    val file = File(temporary.root, "settings.preferences_pb")
    val first = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val preference = ThemePreference.create(first, file)
      preference.setTheme(ThemeChoice.LIGHT)
      preference.setExportPeriod(
          ExportPeriod.Custom(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 9))
      )
    } finally {
      // Joined: DataStore frees the file only when its scope completes.
      first.coroutineContext[Job]?.cancelAndJoin()
    }
    val second = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val reopened = ThemePreference.create(second, file)

      assertEquals(ThemeChoice.LIGHT, reopened.theme.first())
      assertEquals(
          ExportPeriod.Custom(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 9)),
          reopened.exportPeriod.first(),
      )
    } finally {
      second.coroutineContext[Job]?.cancelAndJoin()
    }
  }

  @Test
  fun onlyTheDarkChoiceAndTheSystemDarkModeMakeTheDarkTheme() {
    assertEquals(false, ThemeChoice.LIGHT.isDark(systemDark = true))
    assertEquals(true, ThemeChoice.DARK.isDark(systemDark = false))
    assertEquals(true, ThemeChoice.SYSTEM.isDark(systemDark = true))
    assertEquals(false, ThemeChoice.SYSTEM.isDark(systemDark = false))
  }

  @Test
  fun anExportPresetFollowsTheDayItIsUsedOn() {
    val today = LocalDate.of(2026, 10, 17)
    assertEquals(Period.Day(today), ExportPeriod.Preset(PeriodPreset.DAY).toPeriod(today))
    assertEquals(
        Period.Month(YearMonth.of(2026, 10)),
        ExportPeriod.Preset(PeriodPreset.MONTH).toPeriod(today),
    )
    assertEquals(
        Period.Month(YearMonth.of(2026, 11)),
        ExportPeriod.Preset(PeriodPreset.MONTH).toPeriod(LocalDate.of(2026, 11, 2)),
    )
    assertEquals(Period.Year(2026), ExportPeriod.Preset(PeriodPreset.YEAR).toPeriod(today))
    assertEquals(Period.All, ExportPeriod.Preset(PeriodPreset.ALL).toPeriod(today))
    assertEquals(
        Period.Custom(today, today.plusDays(1)),
        ExportPeriod.Custom(today, today.plusDays(1)).toPeriod(LocalDate.of(2027, 1, 1)),
    )
  }
}
