package com.sadad.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test

/** Frame timings from the record tap until quick add's picker list shows. */
class QuickAddOpenBenchmark {
  @get:Rule val rule = MacrobenchmarkRule()

  @Test fun openWithProfile() = open(profiled)

  @Test fun openWithoutProfile() = open(unprofiled)

  private fun open(mode: CompilationMode) =
      rule.measureRepeated(
          packageName = PACKAGE,
          metrics = listOf(FrameTimingMetric()),
          compilationMode = mode,
          startupMode = StartupMode.WARM,
          iterations = 5,
          setupBlock = {
            pressHome()
            startToHome()
          },
      ) {
        openQuickAdd()
      }
}
