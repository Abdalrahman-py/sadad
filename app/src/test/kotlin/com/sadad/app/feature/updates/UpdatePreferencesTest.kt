package com.sadad.app.feature.updates

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The release, the snooze and the last check survive a restart; that is what keeps the row honest.
 */
class UpdatePreferencesTest {
  @get:Rule val folder = TemporaryFolder()

  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private lateinit var preferences: UpdatePreferences

  private val manifest =
      UpdateManifest(
          channel = "team",
          versionCode = 57,
          versionName = "dev-17-a1b2c3d-team",
          apk = "Sadad-team.apk",
          sha256 = "c".repeat(64),
          sizeBytes = 5_662_310,
      )

  @Before
  fun setUp() {
    preferences =
        UpdatePreferences.create(
            scope,
            { File(folder.root, UpdatePreferences.FILE_NAME) },
            UpdateChannel.TEAM,
        )
  }

  @After fun tearDown() = scope.cancel()

  @Test
  fun theFoundReleaseIsKeptWithItsCheckTime() = runTest {
    preferences.recordCheck(nowMs = 1_000, manifest = manifest)
    val saved = preferences.snapshot.first()
    assertEquals(1_000L, saved.lastCheckMs)
    assertEquals(57, saved.manifest?.versionCode)
  }

  @Test
  fun aSnoozeIsKeptPerVersion() = runTest {
    preferences.snooze(versionCode = 57, untilMs = 9_000)
    val saved = preferences.snapshot.first()
    assertEquals(57, saved.snoozedVersionCode)
    assertEquals(9_000L, saved.snoozedUntilMs)
  }

  @Test
  fun aCheckThatFoundNothingClearsTheReleaseButNotTheSnooze() = runTest {
    preferences.snooze(57, 9_000)
    preferences.recordCheck(nowMs = 2_000, manifest = manifest)
    preferences.recordCheck(nowMs = 3_000, manifest = null)
    val saved = preferences.snapshot.first()
    assertEquals(3_000L, saved.lastCheckMs)
    assertNull(saved.manifest)
    assertEquals(57, saved.snoozedVersionCode)
  }

  @Test
  fun afterTheInstallNothingIsPending() = runTest {
    preferences.snooze(57, 9_000)
    preferences.recordCheck(nowMs = 2_000, manifest = manifest)
    preferences.clear()
    val saved = preferences.snapshot.first()
    assertNull(saved.manifest)
    assertEquals(0, saved.snoozedVersionCode)
    assertEquals(0L, saved.snoozedUntilMs)
  }
}
