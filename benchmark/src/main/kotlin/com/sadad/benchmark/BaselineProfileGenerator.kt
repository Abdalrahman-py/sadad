package com.sadad.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import org.junit.Rule
import org.junit.Test

/**
 * Generates the Baseline Profile: start, Home, People scroll, a person's page, quick add to its
 * amount step. The human commits the output (`./gradlew :app:generateBaselineProfile` on the
 * phone).
 */
class BaselineProfileGenerator {
  @get:Rule val rule = BaselineProfileRule()

  @Test
  fun generate() =
      rule.collect(packageName = PACKAGE) {
        pressHome()
        startToHome()
        openContacts()
        flingContacts(times = 3)
        openFirstContact()
        device.pressBack()
        openQuickAdd()
        typeTenForFirstPerson()
      }
}
