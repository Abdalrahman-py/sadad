package com.sadad.app.feature.settings

import androidx.lifecycle.SavedStateHandle
import com.sadad.app.data.session.StoredInstallation
import com.sadad.app.data.sync.SyncScheduling
import com.sadad.app.data.sync.SyncStatus
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.history.PeriodPreset
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.ledger.Cents
import com.sadad.ledger.Direction
import com.sadad.ledger.PaymentMethod
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

        model.store.editProfile(model.state.value.store)
        model.store.onProfileChange(storeName = "متجر سامي", ownerName = "سامي")
        model.store.saveProfile()

        model.state.first { it.pending == null && it.store.storeName == "متجر سامي" }
        val account = checkNotNull(fixture.graph.accounts.account(STORE_A))
        assertEquals("متجر سامي", account.storeName)
        assertEquals("سامي", account.ownerName)
        val recreated = kit.viewModel()
        val state = recreated.state.first { it.loaded && it.store.storeName.isNotEmpty() }
        assertEquals("متجر سامي", state.store.storeName)
        assertEquals("سامي", state.store.ownerName)
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
        model.store.editProfile(model.state.value.store)
        model.store.onProfileChange(storeName = "متجر ل", ownerName = "")

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

        model.appearance.setTheme(ThemeChoice.DARK)

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

        val state = model.state.first { it.loaded && it.devices.sync is SyncLine.Failed }

        val text = fixture.graph.errorMessages.text("store_suspended")
        assertEquals("المتجر موقوف؛ حُفظت الإدخالات المعلقة.", text)
        assertEquals(SyncLine.Failed(text), state.devices.sync)
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
          assertEquals(
              line,
              model.state.first { it.loaded && it.devices.sync == line }.devices.sync,
          )
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

        model.security.setLockEnabled(true)

        assertEquals(Pending.SetPin, model.state.first { it.pending != null }.pending)
        assertFalse(kit.lock.hasPin())
        assertFalse(model.state.value.security.lockOn)
      }
    }
  }

  @Test
  fun aPinOfTwoDigitsIsRefusedAndAMismatchToo() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        val model = kit.viewModel()
        model.state.first { it.loaded }
        model.security.setLockEnabled(true)
        model.state.first { it.pending == Pending.SetPin }

        model.security.savePin("12", "12")
        assertEquals(PinError.INVALID, model.state.first { it.pinError != null }.pinError)
        model.security.savePin("4839", "4838")
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
        model.security.setLockEnabled(true)
        model.state.first { it.pending == Pending.SetPin }

        model.security.savePin("4839", "4839")

        val state = model.state.first { it.pending == null && it.security.lockOn }
        assertTrue(kit.lock.hasPin())
        assertNull(state.pinError)
      }
    }
  }

  @Test
  fun turningTheLockOffKeepsThePin() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        kit.lock.setPin("4839")
        val model = kit.viewModel()
        model.state.first { it.loaded && it.security.lockOn }

        model.security.setLockEnabled(false)

        model.state.first { !it.security.lockOn }
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
        without.security.setBiometric(true)
        assertFalse(kit.lock.biometricEnabled.first())

        val withSensor = kit.viewModel(biometricAvailable = true)
        withSensor.state.first { it.loaded }
        withSensor.security.setBiometric(true)
        assertEquals(Pending.SetPin, withSensor.state.first { it.pending != null }.pending)

        kit.lock.setPin("4839")
        withSensor.ui.dismiss()
        withSensor.security.setBiometric(true)
        withSensor.state.first { it.security.biometricEnabled }
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
            model.state.first { it.loaded }.data.exportPeriod,
        )

        model.data.selectPreset(PeriodPreset.YEAR)
        model.state.first { it.data.exportPeriod == ExportPeriod.Preset(PeriodPreset.YEAR) }
        model.data.selectCustom(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 9))

        val custom = ExportPeriod.Custom(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 9))
        model.state.first { it.data.exportPeriod == custom }
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

        model.devices.downloadLatest()

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

        assertEquals(1, model.state.first { it.loaded && it.devices.unsent == 1 }.devices.unsent)
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
        model.security.requestPin()
        model.state.first { it.pending == Pending.SetPin }

        for (pin in listOf("12345", "1234567890123", "12a4")) {
          model.security.savePin(pin, pin)
          assertEquals(PinError.INVALID, model.state.first { it.pinError != null }.pinError)
        }
        model.security.savePin("4839", "4839")
        model.state.first { it.pending == null }

        assertTrue(kit.lock.hasPin())
        assertTrue(saved.keys().none { key -> saved.get<Any>(key).toString().contains("4839") })
      }
    }
  }

  @Test
  fun theNewestWaitingEntryIsNamedWithItsContactAndAmount() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        var contact = ""
        fixture.seed { contact = addContact("سارة محمود", null, null, null, null).getOrThrow() }
        fixture.seed {
          addDebt(contact, Direction.RECEIVABLE, Cents(12_000), null, null).getOrThrow()
        }
        val model = kit.viewModel()

        val state = // Rows emit before the view is recomputed: wait for the name, not just the
                    // count.
            model.state.first {
              it.loaded && it.devices.unsent == 2 && it.devices.newest?.name?.isNotEmpty() == true
            }

        assertEquals(2, state.devices.unsent)
        assertEquals(
            QueuedEntry(QueuedKind.DEBT, "سارة محمود", Cents(12_000)),
            state.devices.newest,
        )
      }
    }
  }

  @Test
  fun aQueuedPaymentIsNamedWithItsContactAndAmount() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        var contact = ""
        var debt = ""
        fixture.seed { contact = addContact("سامي", null, null, null, null).getOrThrow() }
        fixture.seed {
          debt = addDebt(contact, Direction.RECEIVABLE, Cents(5_000), null, null).getOrThrow()
        }
        fixture.settleAll()
        fixture.seed { payDebt(debt, Cents(2_000), PaymentMethod.CASH, null).getOrThrow() }
        val model = kit.viewModel()

        val state = // Rows emit before the view is recomputed: wait for the name, not just the
                    // count.
            model.state.first {
              it.loaded && it.devices.unsent == 1 && it.devices.newest?.name?.isNotEmpty() == true
            }

        assertEquals(QueuedEntry(QueuedKind.PAYMENT, "سامي", Cents(2_000)), state.devices.newest)
      }
    }
  }

  @Test
  fun threeWaitingEntriesAreCountedAndTheNewestIsListed() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        repeat(3) { index ->
          fixture.seed { addContact("شخص $index", null, null, null, null).getOrThrow() }
        }
        val model = kit.viewModel()

        val state = model.state.first { it.loaded && it.devices.unsent == 3 }

        assertEquals(3, state.devices.unsent)
        assertEquals(QueuedEntry(QueuedKind.CONTACT_NEW, "شخص 2", null), state.devices.newest)
      }
    }
  }

  @Test
  fun aStoreWithEverythingSentHasNothingWaiting() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        fixture.seed { addContact("سامي", null, null, null, null).getOrThrow() }
        fixture.settleAll()
        val model = kit.viewModel()

        val state = model.state.first { it.loaded }

        assertEquals(0, state.devices.unsent)
        assertNull(state.devices.newest)
      }
    }
  }

  @Test
  fun offlineIsSaidOnTheState() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        val status = MutableStateFlow<SyncStatus>(SyncStatus.Offline)
        val model = kit.viewModel(syncStatus = { status })

        assertEquals(SyncLine.Offline, model.state.first { it.loaded }.devices.sync)
      }
    }
  }

  @Test
  fun theTrashCountIsTheSameNumberTheTrashShows() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        var contact = ""
        fixture.seed { contact = addContact("سامي", null, null, null, null).getOrThrow() }
        fixture.settleAll()
        fixture.seed { archiveContact(contact, "خطأ في التسجيل").getOrThrow() }
        fixture.settleAll()
        val model = kit.viewModel()

        assertEquals(1, model.state.first { it.loaded && it.data.trashCount == 1 }.data.trashCount)
      }
    }
  }

  @Test
  fun aDeviceWithoutDeleteRecordPermissionsMayNotUseTheTrash() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        fixture.graph.accounts.update(STORE_A) {
          it.copy(
              installation =
                  StoredInstallation(
                      status = "active",
                      generation = 1,
                      permissions = mapOf("deleteRecords" to false, "deleteContacts" to false),
                  )
          )
        }
        val model = kit.viewModel()

        assertFalse(model.state.first { it.loaded && !it.data.canDelete }.data.canDelete)
      }
    }
  }

  @Test
  fun theStateSaysWhetherThePhoneHasABiometricSensor() = runTest {
    TestGraph.signedIn().use { fixture ->
      SettingsKit(fixture).use { kit ->
        assertFalse(
            kit.viewModel(biometricAvailable = false)
                .state
                .first { it.loaded }
                .security
                .biometricAvailable
        )
        assertTrue(
            kit.viewModel(biometricAvailable = true)
                .state
                .first { it.loaded }
                .security
                .biometricAvailable
        )
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

        model.devices.downloadLatest()

        val state = model.state.first { it.error != null }
        assertEquals(SettingsError.ACTION_FAILED, state.error)
        assertFalse(state.busy)

        // A later action that works clears the old failure.
        model.appearance.setTheme(ThemeChoice.DARK)
        assertNull(model.state.first { it.theme == ThemeChoice.DARK }.error)
      }
    }
  }
}
