package com.sadad.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test

/** Opens Contacts and flings the list 10 times; frame timings against the 32 ms budget. */
class ContactsScrollBenchmark {
  @get:Rule val rule = MacrobenchmarkRule()

  @Test fun scrollWithProfile() = scroll(profiled)

  @Test fun scrollWithoutProfile() = scroll(unprofiled)

  private fun scroll(mode: CompilationMode) =
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
        flingContacts(times = 10)
      }
}
