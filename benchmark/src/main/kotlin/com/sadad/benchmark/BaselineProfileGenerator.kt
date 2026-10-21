package com.sadad.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import org.junit.Rule
import org.junit.Test

/**
 * Generates the Baseline Profile: start, Home, Contacts scroll, Contact detail, Debt form. The
 * human commits the output after ticket 65 (`./gradlew :app:generateBaselineProfile` on the phone).
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
        openDebtForm()
      }
}
