package com.sadad.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test

/**
 * One idle sync pass (nothing new to push or pull) from Settings' "download latest"; measures the
 * app's `sync_pass` span. The first sync of the seeded store is measured by hand (`sync_pull`, see
 * ticket 65): a fresh install per iteration would need a new device approval each time.
 */
@OptIn(ExperimentalMetricApi::class)
class SyncBenchmark {
  @get:Rule val rule = MacrobenchmarkRule()

  @Test fun idleSyncWithProfile() = idleSync(profiled)

  @Test fun idleSyncWithoutProfile() = idleSync(unprofiled)

  private fun idleSync(mode: CompilationMode) =
      rule.measureRepeated(
          packageName = PACKAGE,
          metrics = listOf(TraceSectionMetric("sync_pass")),
          compilationMode = mode,
          startupMode = StartupMode.WARM,
          iterations = 5,
          setupBlock = {
            pressHome()
            startToHome()
            openSettings()
          },
      ) {
        syncOnce()
      }
}
