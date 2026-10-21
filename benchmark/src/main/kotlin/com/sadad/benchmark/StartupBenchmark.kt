package com.sadad.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test

/** Cold start to Home with data, with and without the Baseline Profile. Needs a phone. */
class StartupBenchmark {
  @get:Rule val rule = MacrobenchmarkRule()

  @Test fun coldStartWithProfile() = coldStart(profiled)

  @Test fun coldStartWithoutProfile() = coldStart(unprofiled)

  private fun coldStart(mode: CompilationMode) =
      rule.measureRepeated(
          packageName = PACKAGE,
          metrics = listOf(StartupTimingMetric()),
          compilationMode = mode,
          startupMode = StartupMode.COLD,
          iterations = 5,
          setupBlock = { pressHome() },
      ) {
        startToHome()
      }
}
