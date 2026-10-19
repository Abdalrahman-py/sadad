package com.sadad.app.feature.auth

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.AwaitingApproval
import com.sadad.app.ui.PasswordChange
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LoginViewModelTest {
  @get:Rule val main = MainDispatcherRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun viewModel(graph: TestGraph, saved: SavedStateHandle = SavedStateHandle()) =
      LoginViewModel(graph.graph, saved, context)

  @Test
  fun aWrongPasswordShowsTheInvalidCredentialsTextAndCreatesNoAccount() = runTest {
    TestGraph.unsigned().use { fixture ->
      fixture.server.enqueue(
          MockResponse.Builder().code(400).body("""{"code":"invalid_credentials"}""").build()
      )
      val model = viewModel(fixture)
      model.onUsernameChange("store-a")
      model.onPasswordChange("wrong-password")

      model.signIn()

      val state = model.state.first { !it.busy && it.error != null }
      assertEquals("اسم المستخدم أو كلمة المرور غير صحيحة.", state.error)
      assertNull(state.destination)
      assertTrue(fixture.graph.accounts.file().accounts.isEmpty())
    }
  }

  @Test
  fun aNewStoreGoesToAwaitingApproval() = runTest {
    TestGraph.unsigned().use { fixture ->
      fixture.enqueueSession(STORE_A)
      val model = viewModel(fixture)
      model.onUsernameChange("store-a")
      model.onPasswordChange("password-store-a")

      model.signIn()

      val state = model.state.first { !it.busy && it.destination != null }
      assertEquals(AwaitingApproval, state.destination)
      assertEquals(STORE_A, fixture.graph.accounts.file().activeStoreUserId)
    }
  }

  @Test
  fun aTemporaryPasswordGoesToPasswordChange() = runTest {
    TestGraph.unsigned().use { fixture ->
      fixture.enqueueSession(STORE_A, forcePasswordChange = true)
      val model = viewModel(fixture)
      model.onUsernameChange("store-a")
      model.onPasswordChange("password-store-a")

      model.signIn()

      val state = model.state.first { !it.busy && it.destination != null }
      assertEquals(PasswordChange, state.destination)
    }
  }

  @Test
  fun theUsernameSurvivesRecreationButThePasswordDoesNot() = runTest {
    TestGraph.unsigned().use { fixture ->
      val saved = SavedStateHandle()
      val first = viewModel(fixture, saved)
      first.onUsernameChange("store-a")
      first.onPasswordChange("private-password")

      val second = viewModel(fixture, saved)

      assertEquals("store-a", second.state.value.username)
      assertEquals("", second.state.value.password)
      assertFalse(saved.keys().any { it.contains("password", ignoreCase = true) })
      assertNull(second.state.value.error)
    }
  }
}
