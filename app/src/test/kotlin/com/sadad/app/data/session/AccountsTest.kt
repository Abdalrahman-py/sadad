package com.sadad.app.data.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.STORE_B
import com.sadad.app.testing.testAccount
import java.io.File
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccountsTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val dataKey = SecretKeySpec(ByteArray(32), "AES")
  private lateinit var scope: CoroutineScope
  private lateinit var file: File
  private lateinit var accounts: Accounts

  @Before
  fun openAccounts() {
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    file = File(context.cacheDir, "accounts-${UUID.randomUUID()}.preferences_pb")
    accounts = Accounts.create(scope, file, dataKey)
  }

  @After
  fun closeAccounts() {
    runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
  }

  @Test
  fun theRefreshTokenIsSealedInTheDataStoreFile() = runTest {
    val token = "refresh-token-${UUID.randomUUID()}"
    accounts.add(testAccount(STORE_A))

    accounts.saveRefreshToken(STORE_A, token)

    assertEquals(token, accounts.refreshToken(STORE_A))
    val stored = String(file.readBytes(), Charsets.ISO_8859_1)
    assertFalse(stored.contains(token))
    assertNotNull(accounts.account(STORE_A)?.sealedRefreshToken)
  }

  @Test
  fun updateChangesOneAccountAndSurvivesANewAccountsOnTheSameFile() = runTest {
    accounts.add(testAccount(STORE_A))
    accounts.add(testAccount(STORE_B))
    accounts.update(STORE_A) { it.copy(storeName = "متجر") }
    accounts.setWelcomeShown()
    accounts.setNotificationsAsked()

    assertEquals("متجر", accounts.account(STORE_A)?.storeName)
    assertEquals("", accounts.account(STORE_B)?.storeName)

    scope.coroutineContext[Job]?.cancelAndJoin()
    val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val reopened = Accounts.create(reopenedScope, file, dataKey)
      val list = reopened.accounts.first()
      assertEquals("متجر", list.single { it.storeUserId == STORE_A }.storeName)
      assertEquals("", list.single { it.storeUserId == STORE_B }.storeName)
      assertTrue(reopened.welcomeShown.first())
      assertTrue(reopened.notificationsAsked.first())
    } finally {
      reopenedScope.coroutineContext[Job]?.cancelAndJoin()
    }
  }

  @Test
  fun removingTheActiveAccountLeavesAnotherActive() = runTest {
    accounts.add(testAccount(STORE_A))
    accounts.add(testAccount(STORE_B))
    accounts.setActive(STORE_A)

    accounts.remove(STORE_A)

    assertNull(accounts.account(STORE_A))
    assertEquals(STORE_B, accounts.file().activeStoreUserId)
  }
}
