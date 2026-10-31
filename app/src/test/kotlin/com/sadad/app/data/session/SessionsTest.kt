package com.sadad.app.data.session

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.data.crypto.InstallationSigner
import com.sadad.app.data.crypto.SoftwareSigner
import com.sadad.app.data.crypto.ecP256KeyPair
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.AwaitingApproval
import com.sadad.app.ui.Login
import com.sadad.app.ui.PasswordChange
import java.io.IOException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionsTest {

  @Test
  fun anAccessTokenThirtySecondsFromExpiryRefreshesOnceBeforeUse() = runTest {
    TestGraph.unsigned().use { fixture ->
      fixture.signIn(STORE_A, expiresInSeconds = 30, refreshToken = "refresh-a")
      fixture.enqueueSession(STORE_A, expiresInSeconds = 3_600, refreshToken = "refresh-b")

      val first = fixture.graph.sessions.accessToken()
      val second = fixture.graph.sessions.accessToken()

      assertNotNull(first)
      assertEquals(first, second)
      assertEquals(2, fixture.server.requestCount)
      assertEquals("refresh-b", fixture.graph.accounts.refreshToken(STORE_A))
    }
  }

  @Test
  fun aReinstallOnTheSamePhoneKeepsTheInstallationId() = runTest {
    // The hardware id survives an uninstall; a random UUID does not.
    val context = ApplicationProvider.getApplicationContext<Context>()
    Settings.Secure.putString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID,
        "device-android-id-1",
    )

    val first = TestGraph.unsigned()
    val firstId =
        try {
          first.signIn(STORE_A)
          checkNotNull(first.graph.accounts.account(STORE_A)).installationId
        } finally {
          first.close()
        }

    val second = TestGraph.unsigned()
    val secondId =
        try {
          second.signIn(STORE_A)
          checkNotNull(second.graph.accounts.account(STORE_A)).installationId
        } finally {
          second.close()
        }

    assertEquals(
        "the same phone signing in again must present the installation it already approved",
        firstId,
        secondId,
    )
  }

  @Test
  fun aRefusedRefreshClearsTheTokenAndGoesToLogin() = runTest {
    TestGraph.unsigned().use { fixture ->
      fixture.signIn(STORE_A, expiresInSeconds = 30)
      fixture.server.enqueue(
          MockResponse.Builder().code(400).body("""{"code":"invalid_grant"}""").build()
      )

      assertNull(fixture.graph.sessions.accessToken())
      assertNull(fixture.graph.accounts.refreshToken(STORE_A))
      assertNotNull(fixture.graph.accounts.account(STORE_A))
      assertEquals(Login, fixture.graph.startRoute())
    }
  }

  @Test
  fun anOfflineRefreshKeepsTheToken() = runTest {
    TestGraph.unsigned().use { fixture ->
      fixture.enqueueSession(STORE_A, expiresInSeconds = 30)
      val session = fixture.graph.sessions.signIn("store-a", "password")
      fixture.server.close()

      assertEquals(session.accessToken, fixture.graph.sessions.accessToken())
      assertNotNull(fixture.graph.accounts.refreshToken(STORE_A))
    }
  }

  @Test
  fun aForcedPasswordChangeComesBeforeApproval() = runTest {
    TestGraph.unsigned().use { fixture ->
      fixture.signIn(STORE_A, forcePasswordChange = true)
      assertEquals(PasswordChange, fixture.graph.startRoute())

      fixture.server.enqueue(MockResponse.Builder().body("""{"ok":true}""").build())
      fixture.enqueueSession(STORE_A, forcePasswordChange = false, refreshToken = "clean")
      fixture.graph.sessions.changePassword("old-password", "new-password-long")

      assertEquals(AwaitingApproval, fixture.graph.startRoute())
    }
  }

  @Test
  fun signingOutKeepsTheAccountAndItsDatabaseButNotTheToken() = runTest {
    TestGraph.signedIn(STORE_A).use { fixture ->
      fixture.seed { addContact("سامي", null, null, null, null) }
      fixture.server.enqueue(MockResponse.Builder().code(204).build())

      fixture.graph.sessions.signOut()

      assertNotNull(fixture.graph.accounts.account(STORE_A))
      assertTrue(fixture.databaseFile(STORE_A).exists())
      assertEquals(1, fixture.graph.sessions.unsentCount())
      assertNull(fixture.graph.accounts.refreshToken(STORE_A))
      assertEquals(Login, fixture.graph.startRoute())
    }
  }

  @Test
  fun signInReturnsOnTheTokenWithoutOpeningTheStore() = runTest {
    TestGraph.unsigned().use { fixture ->
      fixture.enqueueSession(STORE_A)
      val host = RecordingHost()

      sessions(fixture, host).signIn("store-a", "password-store-a")

      assertEquals(0, host.opens)
      assertEquals("refresh-$STORE_A", fixture.graph.accounts.refreshToken(STORE_A))
      assertEquals(STORE_A, fixture.graph.accounts.file().activeStoreUserId)
    }
  }

  @Test
  fun aFailedStepLeavesNoStoreSessionBehind() = runTest {
    TestGraph.unsigned().use { fixture ->
      fixture.enqueueSession(STORE_A)
      val sessions = sessions(fixture, RecordingHost(closeFails = true))

      val failure = runCatching { sessions.signIn("store-a", "password-store-a") }.exceptionOrNull()

      assertTrue(failure is IOException)

      assertNull(fixture.graph.accounts.refreshToken(STORE_A))
      assertNull(sessions.inMemorySession(STORE_A))
    }
  }

  private fun sessions(fixture: TestGraph, host: SessionHost) =
      Sessions(
          fixture.graph.accounts,
          fixture.graph.authApi,
          SoftwareKeys(),
          host,
          deviceLabel = "Test Device",
      )

  private class RecordingHost(private val closeFails: Boolean = false) : SessionHost {
    var opens = 0

    override suspend fun open(account: Account) {
      opens++
    }

    override suspend fun close() {
      if (closeFails) throw IOException("close failed")
    }

    override suspend fun unsentCount(storeUserId: String) = 0
  }

  private class SoftwareKeys : Installations {
    override fun create(keyAlias: String, installationId: String): InstallationSigner =
        SoftwareSigner(installationId, ecP256KeyPair())

    override fun load(keyAlias: String, installationId: String): InstallationSigner? = null

    override fun delete(keyAlias: String) = Unit
  }
}
