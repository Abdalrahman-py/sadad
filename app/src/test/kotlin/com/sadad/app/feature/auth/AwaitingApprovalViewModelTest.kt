package com.sadad.app.feature.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.data.sync.SyncStatus
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.Home
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AwaitingApprovalViewModelTest {
  @get:Rule val main = MainDispatcherRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun anEmptyStaffNameIsRefusedWithoutSavingOrSyncing() = runTest {
    TestGraph.unsigned().use { fixture ->
      fixture.signIn(STORE_A)
      var syncs = 0
      val model =
          AwaitingApprovalViewModel(
              fixture.graph,
              context,
              statusFor = { MutableStateFlow(SyncStatus.AwaitingApproval) },
              sync = { syncs++ },
          )
      model.state.first { !it.loading }

      model.save()

      val state = model.state.first { !it.busy && it.error != null }
      assertEquals("اكتب اسم الشخص على هذا الجهاز.", state.error)
      assertEquals("", fixture.graph.accounts.account(STORE_A)!!.staffName)
      assertEquals(0, syncs)
    }
  }

  @Test
  fun aStaffNameLongerThanEightyCharactersIsRefused() = runTest {
    TestGraph.unsigned().use { fixture ->
      fixture.signIn(STORE_A)
      val model =
          AwaitingApprovalViewModel(
              fixture.graph,
              context,
              statusFor = { MutableStateFlow(SyncStatus.AwaitingApproval) },
              sync = {},
          )
      model.state.first { !it.loading }
      model.onStaffNameChange("ا".repeat(81))

      model.save()

      val state = model.state.first { !it.busy && it.error != null }
      assertEquals("اسم الشخص أطول من 80 حرفًا.", state.error)
    }
  }

  @Test
  fun savingTheStaffNameUpdatesTheAccountThenSyncs() = runTest {
    TestGraph.unsigned().use { fixture ->
      fixture.signIn(STORE_A)
      var syncs = 0
      val model =
          AwaitingApprovalViewModel(
              fixture.graph,
              context,
              statusFor = { MutableStateFlow(SyncStatus.AwaitingApproval) },
              sync = { syncs++ },
          )
      model.state.first { !it.loading }
      model.onStaffNameChange("سامي")
      model.onDeviceLabelChange("هاتف المتجر")

      model.save()

      model.state.first { !it.busy && !it.needsDetails }
      val account = fixture.graph.accounts.account(STORE_A)!!
      assertEquals("سامي", account.staffName)
      assertEquals("هاتف المتجر", account.label)
      assertEquals(1, syncs)
    }
  }

  @Test
  fun eachRefusalStatusShowsItsArabicText() = runTest {
    TestGraph.unsigned().use { fixture ->
      fixture.signIn(STORE_A)
      val statuses = MutableStateFlow<SyncStatus>(SyncStatus.AwaitingApproval)
      val model =
          AwaitingApprovalViewModel(
              fixture.graph,
              context,
              statusFor = { statuses },
              sync = {},
          )
      model.state.first { !it.loading }
      val expected =
          mapOf(
              SyncStatus.Revoked to "الجلسة أو الجهاز غير معتمد.",
              SyncStatus.Suspended to "المتجر موقوف؛ حُفظت الإدخالات المعلقة.",
              SyncStatus.Offline to "لا يوجد اتصال؛ تحقق من الشبكة وحاول مجددًا.",
              SyncStatus.PasswordChangeRequired to "غيّر كلمة المرور المؤقتة أولًا.",
              SyncStatus.Failed("store_permission_denied") to
                  "لا توجد صلاحية لهذا الإجراء على هذا الجهاز.",
          )

      for ((status, text) in expected) {
        statuses.value = status
        val state = model.state.first { it.status == status }
        assertEquals(text, state.statusText)
      }
    }
  }

  @Test
  fun idleWithAnActiveInstallationGoesHome() = runTest {
    TestGraph.unsigned().use { fixture ->
      fixture.signIn(STORE_A)
      fixture.activate(STORE_A)
      val statuses = MutableStateFlow<SyncStatus>(SyncStatus.AwaitingApproval)
      val model =
          AwaitingApprovalViewModel(
              fixture.graph,
              context,
              statusFor = { statuses },
              sync = {},
          )
      model.state.first { it.status == SyncStatus.AwaitingApproval }
      assertFalse(model.state.value.loading)

      statuses.value = SyncStatus.Idle

      val state = model.state.first { it.destination != null }
      assertEquals(Home, state.destination)
    }
  }
}
