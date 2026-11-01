package com.sadad.app.feature.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.Home
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PasswordChangeViewModelTest {
  @get:Rule val main = MainDispatcherRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun viewModel(fixture: TestGraph) =
      PasswordChangeViewModel(fixture.graph.authSource(), context)

  @Test
  fun aNewPasswordOfElevenCharactersIsRefusedWithoutARequest() = runTest {
    TestGraph.unsigned().use { fixture ->
      fixture.signIn(STORE_A, forcePasswordChange = true)
      val requests = fixture.server.requestCount
      val model = viewModel(fixture)
      model.onCurrentChange("old-password-long")
      model.onNewChange("12345678901")
      model.onConfirmChange("12345678901")

      model.change()

      val state = model.state.first { !it.busy && it.error != null }
      assertEquals("استخدم 12 إلى 128 خانة لكلمة المرور.", state.error)
      assertEquals(requests, fixture.server.requestCount)
      assertNull(state.destination)
    }
  }

  @Test
  fun aMismatchWithTheConfirmationIsRefusedWithoutARequest() = runTest {
    TestGraph.unsigned().use { fixture ->
      fixture.signIn(STORE_A, forcePasswordChange = true)
      val requests = fixture.server.requestCount
      val model = viewModel(fixture)
      model.onCurrentChange("old-password-long")
      model.onNewChange("new-password-long")
      model.onConfirmChange("different-password")

      model.change()

      val state = model.state.first { !it.busy && it.error != null }
      assertEquals("كلمتا المرور غير متطابقتين.", state.error)
      assertEquals(requests, fixture.server.requestCount)
    }
  }

  @Test
  fun theSamePasswordIsRefusedWithoutARequest() = runTest {
    TestGraph.unsigned().use { fixture ->
      fixture.signIn(STORE_A, forcePasswordChange = true)
      val requests = fixture.server.requestCount
      val model = viewModel(fixture)
      model.onCurrentChange("same-password-long")
      model.onNewChange("same-password-long")
      model.onConfirmChange("same-password-long")

      model.change()

      val state = model.state.first { !it.busy && it.error != null }
      assertEquals("كلمة المرور الجديدة مطابقة للحالية.", state.error)
      assertEquals(requests, fixture.server.requestCount)
    }
  }

  @Test
  fun anIncorrectCurrentPasswordShowsItsArabicText() = runTest {
    TestGraph.unsigned().use { fixture ->
      fixture.signIn(STORE_A, forcePasswordChange = true)
      fixture.server.enqueue(
          MockResponse.Builder().code(403).body("""{"code":"current_password_incorrect"}""").build()
      )
      val model = viewModel(fixture)
      model.onCurrentChange("wrong-password")
      model.onNewChange("new-password-long")
      model.onConfirmChange("new-password-long")

      model.change()

      val state = model.state.first { !it.busy && it.error != null }
      assertEquals("كلمة المرور الحالية غير صحيحة.", state.error)
      assertNull(state.destination)
    }
  }

  @Test
  fun successRefreshesTheSessionAndGoesHome() = runTest {
    TestGraph.unsigned().use { fixture ->
      fixture.signIn(STORE_A, forcePasswordChange = true)
      fixture.activate(STORE_A)
      fixture.server.enqueue(
          MockResponse.Builder().body("""{"ok":true,"forcePasswordChange":false}""").build()
      )
      fixture.enqueueSession(STORE_A, forcePasswordChange = false, refreshToken = "clean")
      val model = viewModel(fixture)
      model.onCurrentChange("temporary-password")
      model.onNewChange("new-password-long")
      model.onConfirmChange("new-password-long")

      model.change()

      val state = model.state.first { !it.busy && it.destination != null }
      assertEquals(Home, state.destination)
      assertEquals("clean", fixture.graph.accounts.refreshToken(STORE_A))
    }
  }

  @Test
  fun anOfflineChangeKeepsTheUserOnTheScreenWithTheFallbackText() = runTest {
    TestGraph.unsigned().use { fixture ->
      fixture.signIn(STORE_A, forcePasswordChange = true)
      fixture.server.close()
      val model = viewModel(fixture)
      model.onCurrentChange("temporary-password")
      model.onNewChange("new-password-long")
      model.onConfirmChange("new-password-long")

      model.change()

      val state = model.state.first { !it.busy && it.error != null }
      assertEquals("تعذر إتمام الطلب. احتفظ بالإدخال وحاول مجددًا.", state.error)
      assertNull(state.destination)
    }
  }
}
