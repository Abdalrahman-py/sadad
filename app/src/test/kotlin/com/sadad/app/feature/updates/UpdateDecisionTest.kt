package com.sadad.app.feature.updates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Releases are ordered by code, and a snooze never outlives the release it was given for. */
class UpdateDecisionTest {
  private val manifest =
      UpdateManifest(
          channel = "team",
          versionCode = 57,
          versionName = "dev-17-a1b2c3d-team",
          apk = "Sadad-team.apk",
          sha256 = "a".repeat(64),
          sizeBytes = 5_662_310,
      )

  @Test fun aHigherCodeIsAnUpdate() = assertTrue(isNewer(56, manifest))

  @Test
  fun theSameOrAnOlderCodeIsNotAnUpdate() {
    assertFalse(isNewer(57, manifest))
    assertFalse(isNewer(58, manifest))
    // A downgrade is never offered; Android would refuse it in any case.
    assertFalse(isNewer(1_000, manifest))
  }

  @Test
  fun aSnoozedReleaseIsSilentForAWeekAndThenReturns() {
    val week = UpdateCenter.SNOOZE_MS
    assertFalse(shouldPrompt(manifest, 57, snoozedUntilMs = 1_000 + week, nowMs = 999 + week))
    assertTrue(shouldPrompt(manifest, 57, snoozedUntilMs = 1_000 + week, nowMs = 1_000 + week))
  }

  @Test
  fun aNewerReleaseIgnoresTheOldSnooze() {
    assertTrue(
        shouldPrompt(
            manifest,
            snoozedVersionCode = 42,
            snoozedUntilMs = Long.MAX_VALUE,
            nowMs = 0,
        )
    )
  }

  @Test
  fun nothingIsPromptedWithoutARelease() {
    assertFalse(shouldPrompt(null, 0, 0, 0))
  }
}
