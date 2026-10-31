package com.sadad.app.testing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.AppGraph
import com.sadad.app.data.crypto.Base64Url
import com.sadad.app.data.crypto.DataKey
import com.sadad.app.data.crypto.InstallationSigner
import com.sadad.app.data.crypto.SoftwareSigner
import com.sadad.app.data.crypto.ecP256KeyPair
import com.sadad.app.data.db.Events
import com.sadad.app.data.db.LedgerDatabase
import com.sadad.app.data.ledger.LedgerRepository
import com.sadad.app.data.ledger.OutboxState
import com.sadad.app.data.ledger.receiptOf
import com.sadad.app.data.net.AuthApi
import com.sadad.app.data.net.LedgerApi
import com.sadad.app.data.session.Account
import com.sadad.app.data.session.Accounts
import com.sadad.app.data.session.Installations
import com.sadad.app.data.session.StoreScope
import com.sadad.app.data.session.StoredInstallation
import com.sadad.ledger.ReceiptStatus
import java.io.File
import java.security.KeyPair
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

const val STORE_A = "11111111-1111-4111-8111-111111111111"
const val STORE_B = "22222222-2222-4222-8222-222222222222"

/**
 * The one fixture every screen test builds on: a real `AppGraph` wired to a `MockWebServer`, a
 * temporary DataStore and file-backed Room (the remove and isolation tests need real files), and
 * software installation keys in place of the Keystore.
 */
class TestGraph
private constructor(
    val graph: AppGraph,
    val server: MockWebServer,
    val accountsFile: File,
    private val graphScope: CoroutineScope,
    private val dataStoreScope: CoroutineScope,
) : AutoCloseable {
  private val testJobs = mutableListOf<Job>()

  /** Stop test-owned collectors before closing the store's Room database. */
  fun closeWithStore(scope: CoroutineScope) {
    testJobs += checkNotNull(scope.coroutineContext[Job])
  }

  val context: Context
    get() = ApplicationProvider.getApplicationContext()

  /** The file-backed database one store uses; the same name `LedgerDatabase.open` gives it. */
  fun databaseFile(storeUserId: String): File = context.getDatabasePath("ledger-$storeUserId.db")

  /** Answers the next `/auth/v1/token?grant_type=password` with [storeUserId]'s session. */
  fun enqueueSession(
      storeUserId: String = STORE_A,
      expiresInSeconds: Long = 3_600,
      forcePasswordChange: Boolean = false,
      refreshToken: String = "refresh-$storeUserId",
  ) {
    val token =
        "e30." +
            Base64Url.encode(
                """{"app_metadata":{"force_password_change":$forcePasswordChange}}""".toByteArray()
            ) +
            ".signature"
    server.enqueue(
        MockResponse.Builder()
            .body(
                """{"access_token":"$token","refresh_token":"$refreshToken","expires_at":${Instant.now().epochSecond + expiresInSeconds},"user":{"id":"$storeUserId"}}"""
            )
            .build()
    )
  }

  suspend fun signIn(
      storeUserId: String = STORE_A,
      username: String = "store-$storeUserId",
      expiresInSeconds: Long = 3_600,
      forcePasswordChange: Boolean = false,
      refreshToken: String = "refresh-$storeUserId",
  ): StoreScope {
    enqueueSession(storeUserId, expiresInSeconds, forcePasswordChange, refreshToken)
    graph.sessions.signIn(username, "password-$username")
    // Sign-in leaves opening the store to the route; tests that seed need it open.
    return graph.openStore(checkNotNull(graph.accounts.account(storeUserId)))
  }

  /**
   * Marks the open store's installation active and gives its Ledger an epoch, as enroll + sync do.
   */
  suspend fun activate(
      storeUserId: String = graph.current.value?.account?.storeUserId ?: error("no open store")
  ): StoreScope {
    graph.accounts.update(storeUserId) {
      it.copy(installation = StoredInstallation("active", generation = 1))
    }
    val account = graph.accounts.accounts.first().first { it.storeUserId == storeUserId }
    val scope = graph.openStore(account)
    scope.repository.applyPage(Events.page(emptyList()))
    scope.setEpoch(Events.EPOCH)
    return scope
  }

  /** Calls the repository's write methods on the open store. */
  suspend fun seed(block: suspend LedgerRepository.() -> Unit) {
    val scope = graph.current.value ?: error("no open store")
    scope.repository.block()
  }

  /** Marks every queued row accepted and folds the matching events into the Ledger. */
  suspend fun settleAll() {
    val scope = graph.current.value ?: return
    val rows = scope.outbox.snapshot().filter { it.state == OutboxState.QUEUED }
    if (rows.isEmpty()) return
    val first = (scope.database.eventDao().all().lastOrNull()?.sequence ?: 0L) + 1
    scope.outbox.settle(
        rows.mapIndexed { index, row ->
          receiptOf(row.command, ReceiptStatus.ACCEPTED, sequence = first + index)
        }
    )
    scope.repository.applyPage(
        Events.page(rows.mapIndexed { index, row -> Events.accepted(row.command, first + index) })
    )
  }

  override fun close() {
    runBlocking {
      for (job in testJobs.asReversed()) job.cancelAndJoin()
      graph.closeStore()
      graphScope.cancel()
      dataStoreScope.coroutineContext[Job]?.cancelAndJoin()
    }
    server.close()
  }

  companion object {
    /** No store yet: for sign-in, refresh and session tests. */
    fun unsigned(online: () -> Boolean = { true }): TestGraph {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val server = MockWebServer().apply { start() }
      val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
      val graphScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val accountsFile =
          File(context.cacheDir, "accounts-${UUID.randomUUID()}/accounts.preferences_pb").apply {
            parentFile?.mkdirs()
          }
      val dataKey =
          DataKey(SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES"))
      val client = OkHttpClient()
      val base = server.url("/").toString()
      val graph =
          AppGraph(
              context = context,
              client = client,
              dataKey = dataKey,
              accounts = Accounts.create(dataStoreScope, accountsFile, dataKey.key),
              authApi = AuthApi(client, base, "publishable"),
              ledgerApi = LedgerApi(client, base, "publishable"),
              installations = SoftwareInstallations(),
              openDatabase = { LedgerDatabase.open(context, it) },
              deleteDatabase = { context.deleteDatabase("ledger-$it.db") },
              online = online,
              deviceLabel = "Test Device",
              scope = graphScope,
          )
      return TestGraph(graph, server, accountsFile, graphScope, dataStoreScope)
    }

    /** The default fixture: one signed-in store with an active installation and an epoch. */
    suspend fun signedIn(
        storeUserId: String = STORE_A,
        username: String = "store-$storeUserId",
        online: () -> Boolean = { true },
    ): TestGraph {
      val graph = unsigned(online)
      graph.signIn(storeUserId, username)
      graph.activate(storeUserId)
      return graph
    }
  }
}

/**
 * The Keystore stand-in: keeps each alias's software key pair in memory for the test's lifetime.
 */
private class SoftwareInstallations : Installations {
  private val keys = mutableMapOf<String, KeyPair>()

  override fun create(keyAlias: String, installationId: String): InstallationSigner =
      SoftwareSigner(installationId, ecP256KeyPair().also { keys[keyAlias] = it })

  override fun load(keyAlias: String, installationId: String): InstallationSigner? =
      keys[keyAlias]?.let { SoftwareSigner(installationId, it) }

  override fun delete(keyAlias: String) {
    keys.remove(keyAlias)
  }
}

/** One stored store account with the defaults sign-in gives it. */
fun testAccount(
    storeUserId: String,
    username: String = "store-$storeUserId",
): Account =
    Account(
        storeUserId = storeUserId,
        username = username,
        installationId = UUID.randomUUID().toString(),
        keyAlias = "installation-$storeUserId",
        label = "Test Device",
    )
