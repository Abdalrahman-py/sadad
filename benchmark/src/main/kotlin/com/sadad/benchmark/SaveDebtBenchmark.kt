package com.sadad.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test

/**
 * Saves a 10 ₪ Debt for the first Contact and measures the app's `save_debt` span (write < 100 ms).
 * Each iteration adds one Debt to the seeded test store.
 */
@OptIn(ExperimentalMetricApi::class)
class SaveDebtBenchmark {
  @get:Rule val rule = MacrobenchmarkRule()

  @Test fun saveDebtWithProfile() = saveDebt(profiled)

  @Test fun saveDebtWithoutProfile() = saveDebt(unprofiled)

  private fun saveDebt(mode: CompilationMode) =
      rule.measureRepeated(
          packageName = PACKAGE,
          metrics = listOf(TraceSectionMetric("save_debt")),
          compilationMode = mode,
          startupMode = StartupMode.WARM,
          iterations = 5,
          setupBlock = {
            startToHome()
            openContacts()
            openFirstContact()
          },
      ) {
        saveTenShekelDebt()
      }
}
