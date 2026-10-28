package com.sadad.app.data.sync

import com.sadad.app.data.db.Events
import com.sadad.ledger.InstallationName
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Sync stores every device's names after each pass, so any entry can name its recorder. */
@RunWith(RobolectricTestRunner::class)
class SyncInstallationsTest {
  @Test
  fun aSyncStoresTheLatestDeviceNames() = runTest {
    SyncFixture.create().use { f ->
      val names =
          listOf(
              InstallationName("5e0f2a1b-3c4d-4e5f-8a6b-7c8d9e0f1a2b", "جهاز الكاشير", "سامر"),
              InstallationName("1f2e3d4c-5b6a-4978-8d6c-5b4a3f2e1d0c", "هاتف ثان", "سامي"),
          )
      f.page(Events.page(emptyList()).copy(installations = names))
      assertEquals(SyncStatus.Idle, f.engine.pass(f.scope))
      assertEquals(
          names.associateBy { it.id },
          f.scope.database.installationNames().first(),
      )

      // A second sync with a renamed label replaces what the first one stored.
      val renamed =
          listOf(
              InstallationName("5e0f2a1b-3c4d-4e5f-8a6b-7c8d9e0f1a2b", "جهاز المدير", "سامر"),
              InstallationName("1f2e3d4c-5b6a-4978-8d6c-5b4a3f2e1d0c", "هاتف ثان", "سامي"),
          )
      f.page(Events.page(emptyList()).copy(installations = renamed))
      assertEquals(SyncStatus.Idle, f.engine.pass(f.scope))
      assertEquals(
          renamed.associateBy { it.id },
          f.scope.database.installationNames().first(),
      )
    }
  }
}
