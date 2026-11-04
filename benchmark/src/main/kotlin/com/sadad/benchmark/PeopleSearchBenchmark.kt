package com.sadad.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test

/** Types a name into People's search one letter at a time; frame timings while the list filters. */
class PeopleSearchBenchmark {
  @get:Rule val rule = MacrobenchmarkRule()

  @Test fun searchWithProfile() = search(profiled)

  @Test fun searchWithoutProfile() = search(unprofiled)

  private fun search(mode: CompilationMode) =
      rule.measureRepeated(
          packageName = PACKAGE,
          metrics = listOf(FrameTimingMetric()),
          compilationMode = mode,
          startupMode = StartupMode.WARM,
          iterations = 5,
          setupBlock = {
            pressHome()
            startToHome()
            openContacts()
          },
      ) {
        typeInPeopleSearch("محمد")
      }
}
