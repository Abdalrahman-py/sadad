package com.sadad.app.feature.settings

import androidx.lifecycle.SavedStateHandle
import com.sadad.app.data.sync.SyncScheduling
import com.sadad.app.data.sync.SyncStatus
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.history.PeriodPreset
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class SettingsViewModelTest {
  @get:Rule val main = MainDispatcherRule()

  @Test
  fun savingTheStoreNameUpdatesTheAccountAndSurvivesANewViewModel() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        val model = kit.viewModel()
        model.state.first { it.loaded }

        model.editProfile()
        model.onProfileChange(storeName = "متجر سامي", ownerName = "سامي")
        model.saveProfile()

        model.state.first { it.pending == null && it.storeName == "متجر سامي" }
        val account = checkNotNull(fixture.graph.accounts.account(STORE_A))
        assertEquals("متجر سامي", account.storeName)
        assertEquals("سامي", account.ownerName)
        val recreated = kit.viewModel()
        val state = recreated.state.first { it.loaded && it.storeName.isNotEmpty() }
        assertEquals("متجر سامي", state.storeName)
        assertEquals("سامي", state.ownerName)
      }
    }
  }

  @Test
  fun theProfileDraftSurvivesARecreatedViewModel() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        val saved = SavedStateHandle()
        val model = kit.viewModel(saved)
        model.state.first { it.loaded }
        model.editProfile()
        model.onProfileChange(storeName = "متجر ل", ownerName = "")

        val recreated = kit.viewModel(saved)
        val state = recreated.state.first { it.loaded && it.pending is Pending.Profile }

        assertEquals(Pending.Profile("متجر ل", ""), state.pending)
      }
    }
  }

  @Test
  fun theThemeChoiceIsKeptInTheSettingsFile() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        val model = kit.viewModel()
        assertEquals(ThemeChoice.SYSTEM, model.state.first { it.loaded }.theme)

        model.setTheme(ThemeChoice.DARK)

        model.state.first { it.theme == ThemeChoice.DARK }
        assertEquals("settings.preferences_pb", ThemePreference.FILE_NAME)
        assertEquals(ThemeChoice.DARK, kit.preferences.theme.first())
      }
    }
  }

  @Test
  fun aFailedSyncShowsTheErrorsJsonText() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        val status = MutableStateFlow<SyncStatus>(SyncStatus.Failed("store_suspended"))
        val model = kit.viewModel(syncStatus = { status })

        val state = model.state.first { it.loaded && it.sync is SyncLine.Failed }

        val text = fixture.graph.errorMessages.text("store_suspended")
        assertEquals("المتجر موقوف؛ حُفظت الإدخالات المعلقة.", text)
        assertEquals(SyncLine.Failed(text), state.sync)
      }
    }
  }

  @Test
  fun everySyncStatusHasALine() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        val status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
        val model = kit.viewModel(syncStatus = { status })
        val expected =
            mapOf(
                SyncStatus.Idle to SyncLine.Idle,
                SyncStatus.Running to SyncLine.Running,
                SyncStatus.Offline to SyncLine.Offline,
                SyncStatus.AwaitingApproval to SyncLine.AwaitingApproval,
                SyncStatus.Revoked to SyncLine.Revoked,
                SyncStatus.Suspended to SyncLine.Suspended,
                SyncStatus.PasswordChangeRequired to SyncLine.PasswordChangeRequired,
                SyncStatus.SignedOut to SyncLine.SignedOut,
                SyncStatus.Retry to SyncLine.Retry,
            )

        for ((source, line) in expected) {
          status.value = source
          assertEquals(line, model.state.first { it.loaded && it.sync == line }.sync)
        }
      }
    }
  }

  @Test
  fun turningTheLockOnWithoutAPinAsksForOne() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        val model = kit.viewModel()
        model.state.first { it.loaded }

        model.setLockEnabled(true)

        assertEquals(Pending.SetPin, model.state.first { it.pending != null }.pending)
        assertFalse(kit.lock.hasPin())
        assertFalse(model.state.value.lockOn)
      }
    }
  }

  @Test
  fun aPinOfTwoDigitsIsRefusedAndAMismatchToo() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        val model = kit.viewModel()
        model.state.first { it.loaded }
        model.setLockEnabled(true)
        model.state.first { it.pending == Pending.SetPin }

        model.savePin("12", "12")
        assertEquals(PinError.INVALID, model.state.first { it.pinError != null }.pinError)
        model.savePin("483927", "483928")
        assertEquals(
            PinError.MISMATCH,
            model.state.first { it.pinError == PinError.MISMATCH }.pinError,
        )

        assertFalse(kit.lock.hasPin())
        assertEquals(Pending.SetPin, model.state.value.pending)
      }
    }
  }

  @Test
  fun aValidPinIsSavedAndTheLockTurnsOn() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        val model = kit.viewModel()
        model.state.first { it.loaded }
        model.setLockEnabled(true)
        model.state.first { it.pending == Pending.SetPin }

        model.savePin("483927", "483927")

        val state = model.state.first { it.pending == null && it.lockOn }
        assertTrue(kit.lock.hasPin())
        assertNull(state.pinError)
      }
    }
  }

  @Test
  fun turningTheLockOffKeepsThePin() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        kit.lock.setPin("483927")
        val model = kit.viewModel()
        model.state.first { it.loaded && it.lockOn }

        model.setLockEnabled(false)

        model.state.first { !it.lockOn }
        assertTrue(kit.lock.hasPin())
      }
    }
  }

  @Test
  fun biometricNeedsAPinAndAnAvailableSensor() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        val without = kit.viewModel(biometricAvailable = false)
        without.state.first { it.loaded }
        without.setBiometric(true)
        assertFalse(kit.lock.biometricEnabled.first())

        val withSensor = kit.viewModel(biometricAvailable = true)
        withSensor.state.first { it.loaded }
        withSensor.setBiometric(true)
        assertEquals(Pending.SetPin, withSensor.state.first { it.pending != null }.pending)

        kit.lock.setPin("483927")
        withSensor.dismiss()
        withSensor.setBiometric(true)
        withSensor.state.first { it.biometricEnabled }
        assertTrue(kit.lock.biometricEnabled.first())
      }
    }
  }

  @Test
  fun theExportPeriodIsSavedAndFollowsTheClock() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        val model = kit.viewModel()
        assertEquals(
            ExportPeriod.Preset(PeriodPreset.MONTH),
            model.state.first { it.loaded }.exportPeriod,
        )

        model.selectPreset(PeriodPreset.YEAR)
        model.state.first { it.exportPeriod == ExportPeriod.Preset(PeriodPreset.YEAR) }
        model.selectCustom(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 9))

        val custom = ExportPeriod.Custom(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 9))
        model.state.first { it.exportPeriod == custom }
        assertEquals(custom, kit.preferences.exportPeriod.first())
      }
    }
  }

  @Test
  fun downloadingTheLatestRecordsAsksTheSchedulerForTheOpenStore() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        val model = kit.viewModel()
        model.state.first { it.loaded }

        model.downloadLatest()

        assertEquals(listOf(STORE_A), kit.recording.syncNow)
      }
    }
  }

  @Test
  fun theUnsentCountComesFromTheOutbox() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        fixture.seed { addContact("سامي", null, null, null, null).getOrThrow() }
        val model = kit.viewModel()

        assertEquals(1, model.state.first { it.loaded && it.unsent == 1 }.unsent)
      }
    }
  }

  @Test
  fun aPinWithThirteenDigitsOrLettersIsRefusedAndNeverReachesTheSavedState() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        val saved = SavedStateHandle()
        val model = kit.viewModel(saved)
        model.state.first { it.loaded }
        model.requestPin()
        model.state.first { it.pending == Pending.SetPin }

        for (pin in listOf("1234567890123", "12a4")) {
          model.savePin(pin, pin)
          assertEquals(PinError.INVALID, model.state.first { it.pinError != null }.pinError)
        }
        model.savePin("483927", "483927")
        model.state.first { it.pending == null }

        assertTrue(kit.lock.hasPin())
        assertTrue(saved.keys().none { key -> saved.get<Any>(key).toString().contains("483927") })
      }
    }
  }

  @Test
  fun anActionThatFailsEndsWithAMessageInsteadOfCrashing() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        // WorkManager refuses to enqueue when it is not initialised.
        kit.scheduler =
            object : SyncScheduling {
              override fun syncNow(storeUserId: String) = error("WorkManager is not initialised")

              override fun schedulePeriodic(storeUserId: String) = Unit

              override fun cancel(storeUserId: String) = Unit
            }
        val model = kit.viewModel()
        model.state.first { it.loaded }

        model.downloadLatest()

        val state = model.state.first { it.error != null }
        assertEquals(SettingsError.ACTION_FAILED, state.error)
        assertFalse(state.busy)
      }
    }
  }
}
