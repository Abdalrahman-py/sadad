package com.sadad.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import com.sadad.app.data.crypto.DataKey
import com.sadad.app.data.db.LedgerDatabase
import com.sadad.app.data.db.LedgerStore
import com.sadad.app.data.ledger.ContactRecords
import com.sadad.app.data.ledger.Outbox
import com.sadad.app.data.ledger.OutboxState
import com.sadad.app.data.net.ApiHttpClient
import com.sadad.app.data.net.AuthApi
import com.sadad.app.data.net.ErrorMessages
import com.sadad.app.data.net.LeaseVerifier
import com.sadad.app.data.net.LedgerApi
import com.sadad.app.data.session.Account
import com.sadad.app.data.session.Accounts
import com.sadad.app.data.session.Installations
import com.sadad.app.data.session.KeystoreInstallations
import com.sadad.app.data.session.SessionHost
import com.sadad.app.data.session.Sessions
import com.sadad.app.data.session.StoreScope
import com.sadad.app.data.sync.LeaseStore
import com.sadad.app.data.sync.SyncEngine
import com.sadad.app.data.sync.SyncScheduler
import com.sadad.app.data.sync.SyncScheduling
import com.sadad.app.data.sync.SyncStatus
import com.sadad.app.feature.notifications.DueReminders
import com.sadad.app.feature.photo.PhotoKind
import com.sadad.app.feature.photo.PhotoStore
import com.sadad.app.feature.trash.TrashHidden
import com.sadad.app.ui.AwaitingApproval
import com.sadad.app.ui.Home
import com.sadad.app.ui.Login
import com.sadad.app.ui.PasswordChange
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * The app's one manual DI container. It owns the shared clients and the currently open store, and
 * answers which screen the app starts on. One store is open at a time; its scope is never shared.
 */
class AppGraph
internal constructor(
    private val context: Context,
    val client: OkHttpClient,
    val dataKey: DataKey,
    val accounts: Accounts,
    val authApi: AuthApi,
    val ledgerApi: LedgerApi,
    private val installations: Installations,
    private val openDatabase: (String) -> LedgerDatabase,
    private val deleteDatabase: (String) -> Unit,
    private val online: () -> Boolean,
    private val deviceLabel: String,
    private val scope: CoroutineScope,
    private val syncScheduler: SyncScheduling? = null,
    leaseDirectory: File? = null,
    private val leaseVerifier: LeaseVerifier =
        LeaseVerifier(BuildConfig.LEASE_PUBLIC_KEY_X, BuildConfig.LEASE_PUBLIC_KEY_Y),
    private val bootCount: () -> Int = {
      Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    },
    /** The wall clock offline leases are checked against; a test moves it. */
    private val now: () -> Long = System::currentTimeMillis,
    /** Stable across a reinstall of the same signing key; see DeviceIdentity. */
    private val deviceSeed: () -> String? = { androidId(context) },
) : SessionHost {
  val errorMessages: ErrorMessages by lazy { ErrorMessages(context) }

  val sessions: Sessions by lazy {
    Sessions(
        accounts,
        authApi,
        installations,
        this,
        deviceLabel,
        installationSeed = deviceSeed,
    )
  }

  private val mutableCurrent = MutableStateFlow<StoreScope?>(null)
  private val storeMutex = Mutex()
  private val stores = mutableMapOf<String, StoreScope>()
  private val syncUsers = mutableMapOf<StoreScope, Int>()
  private val leaseDirectory = leaseDirectory ?: File(context.cacheDir, "sync-${UUID.randomUUID()}")
  private val leases = ConcurrentHashMap<String, LeaseStore>()
  internal val syncEngine by lazy { SyncEngine(accounts, sessions, ledgerApi, ::leaseFor) }

  fun syncStatus(storeUserId: String): StateFlow<SyncStatus> = syncEngine.status(storeUserId)

  /**
   * When the offline lease of [account] ends, in epoch millis, or `null` when there is none. Read-
   * only: Home names the time the store may keep recording without a connection.
   */
  fun offlineUntil(account: Account): Long? = leaseFor(account.storeUserId).expiresAt(account)

  internal fun leaseFor(storeUserId: String): LeaseStore =
      leases.computeIfAbsent(storeUserId) {
        LeaseStore(
            File(leaseDirectory, "lease-$it.preferences_pb"),
            scope,
            leaseVerifier,
            bootCount,
            now,
        )
      }

  /** Background sync reuses a store's scope without changing the active account or UI. */
  suspend fun syncStore(storeUserId: String): SyncStatus =
      withContext(Dispatchers.IO) {
        val opened = storeMutex.withLock {
          val account = accounts.account(storeUserId) ?: return@withContext SyncStatus.SignedOut
          (stores[storeUserId] ?: createStore(account).also { stores[storeUserId] = it }).also {
            syncUsers[it] = (syncUsers[it] ?: 0) + 1
          }
        }
        try {
          // A store the start route opened may still be reading its rows.
          opened.repository.awaitLoaded()
          syncEngine.pass(opened)
        } finally {
          withContext(NonCancellable) {
            storeMutex.withLock {
              val remaining = (syncUsers[opened] ?: 1) - 1
              if (remaining == 0) syncUsers.remove(opened) else syncUsers[opened] = remaining
              if (
                  remaining == 0 &&
                      mutableCurrent.value !== opened &&
                      stores[storeUserId] === opened
              ) {
                stores.remove(storeUserId)
                opened.close()
              }
            }
          }
        }
      }

  val current: StateFlow<StoreScope?> = mutableCurrent.asStateFlow()

  /**
   * The first screen: Login without a store or a session, then the password change the server asked
   * for, then approval, then Home.
   */
  suspend fun startRoute(): Any {
    val file = accounts.file()
    if (file.accounts.isEmpty()) return Login
    val activeId = file.activeStoreUserId ?: file.accounts.first().storeUserId
    val account = file.accounts.firstOrNull { it.storeUserId == activeId } ?: return Login
    val session = sessions.inMemorySession(activeId)
    if (session == null && account.sealedRefreshToken == null) return Login
    if (session?.forcePasswordChange == true) return PasswordChange
    if (account.installation?.status != "active") return AwaitingApproval
    // Home reads its rows behind the lock screen: the PIN pad must not wait for the ledger.
    if (mutableCurrent.value?.account?.storeUserId != account.storeUserId) {
      openStore(account, awaitRows = false)
    }
    return Home
  }

  /**
   * Closes the open store, then opens [account]'s database, outbox and repository. It returns with
   * the rows read, unless [awaitRows] is false: then they load in the background and the
   * repository's view stays null until they are in.
   */
  suspend fun openStore(account: Account, awaitRows: Boolean = true): StoreScope =
      withContext(Dispatchers.IO) {
        storeMutex.withLock {
          closeCurrent()
          val opened =
              stores[account.storeUserId]
                  ?: createStore(account, awaitRows).also { stores[account.storeUserId] = it }
          mutableCurrent.value = opened
          opened
        }
      }

  private suspend fun createStore(account: Account, awaitRows: Boolean = true): StoreScope {
    val signer =
        checkNotNull(installations.load(account.keyAlias, account.installationId)) {
          "the installation key for store ${account.storeUserId} is missing"
        }
    val lease = leaseFor(account.storeUserId)
    lease.load(account)
    val database = openDatabase(account.storeUserId)
    val store = LedgerStore(database)
    val outbox = Outbox(database.outboxDao(), dataKey.key)
    val storeJobs =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    val storeScope =
        StoreScope(account, database, store, outbox, signer, accounts, online, storeJobs, lease) {
          syncScheduler?.syncNow(account.storeUserId)
        }
    if (!awaitRows) {
      // A failed read still ends the app loudly, as it did on the splash, not as a Home that never
      // fills.
      storeJobs.launch { loadRows(storeScope) }
      return storeScope
    }
    try {
      loadRows(storeScope)
      return storeScope
    } catch (error: Throwable) {
      withContext(NonCancellable) { storeScope.close() }
      throw error
    }
  }

  /** The epoch goes in first, so sync that waits for the rows never sees the store without it. */
  private suspend fun loadRows(storeScope: StoreScope) {
    storeScope.setEpoch(storeScope.database.metaDao().get()?.epoch)
    storeScope.repository.load()
    syncScheduler?.schedulePeriodic(storeScope.account.storeUserId)
  }

  suspend fun closeStore() = storeMutex.withLock { closeCurrent() }

  private suspend fun closeCurrent() {
    val open = mutableCurrent.value ?: return
    mutableCurrent.value = null
    stores.remove(open.account.storeUserId)
    open.close()
  }

  suspend fun switchStore(storeUserId: String) = sessions.switchTo(storeUserId)

  suspend fun unsentCount(): Int = sessions.unsentCount()

  /**
   * Removes a store from this phone: its key, database file and account. A queued outbox refuses
   * unless the caller has confirmed twice.
   */
  suspend fun removeStore(storeUserId: String, confirmedTwice: Boolean = false): Boolean =
      withContext(Dispatchers.IO) {
        storeMutex.withLock {
          val account = accounts.account(storeUserId) ?: return@withContext true
          if (queuedCount(storeUserId) > 0 && !confirmedTwice) return@withContext false
          syncScheduler?.cancel(storeUserId)
          leases[storeUserId]?.clear()
          val open = stores.remove(storeUserId)
          if (mutableCurrent.value?.account?.storeUserId == storeUserId) {
            sessions.leave(storeUserId)
            mutableCurrent.value = null
          }
          open?.close()
          installations.delete(account.keyAlias)
          deleteDatabase(storeUserId)
          accounts.remove(storeUserId)
          // Everything the app kept for this store goes with it: a store added again under the
          // same id must not inherit an old photo, hidden ids or reminders.
          val photos = PhotoStore(context)
          PhotoKind.entries.forEach { kind -> runCatching { photos.delete(storeUserId, kind) } }
          TrashHidden.forget(context, storeUserId)
          DueReminders.get(context).forget(storeUserId)
          true
        }
      }

  override suspend fun open(account: Account) {
    openStore(account)
  }

  override suspend fun close() {
    closeStore()
  }

  override suspend fun unsentCount(storeUserId: String): Int = storeMutex.withLock {
    queuedCount(storeUserId)
  }

  private suspend fun queuedCount(storeUserId: String): Int {
    val open = stores[storeUserId]
    if (open?.account?.storeUserId == storeUserId) {
      return open.outbox.snapshot().count { it.state == OutboxState.QUEUED }
    }
    val database = openDatabase(storeUserId)
    return try {
      Outbox(database.outboxDao(), dataKey.key).snapshot().count { it.state == OutboxState.QUEUED }
    } finally {
      withContext(Dispatchers.IO) { database.close() }
    }
  }

  companion object {
    fun create(context: Context): AppGraph {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val dataKey = DataKey.get(context)
      val client = ApiHttpClient.client
      val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
      return AppGraph(
          context = context,
          client = client,
          dataKey = dataKey,
          accounts =
              Accounts.create(scope, File(context.filesDir, Accounts.FILE_NAME), dataKey.key),
          authApi = AuthApi(client, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY),
          ledgerApi =
              LedgerApi(client, BuildConfig.API_BASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY),
          installations = KeystoreInstallations,
          openDatabase = { LedgerDatabase.open(context, it) },
          deleteDatabase = { context.deleteDatabase("ledger-$it.db") },
          online = { isOnline(context) },
          deviceLabel = Build.MODEL,
          scope = scope,
          syncScheduler = SyncScheduler(context),
          leaseDirectory = context.filesDir,
          bootCount = { boot },
      )
    }

    private fun isOnline(context: Context): Boolean {
      val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
      val network = manager.activeNetwork ?: return false
      return manager
          .getNetworkCapabilities(network)
          ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
  }
}

/**
 * The device value that survives a reinstall of the same signing key. `ANDROID_ID` is stable per
 * app-signing key + user since Android 8; a device that withholds it falls back to a random id.
 */
private fun androidId(context: Context): String? = runCatching {
  Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
}
    .getOrNull()

/** The open store's contact records; null while no store is open. */
@OptIn(ExperimentalCoroutinesApi::class)
fun AppGraph.contactRecords(): Flow<ContactRecords?> = current.flatMapLatest { scope ->
  scope?.repository?.contactRecords ?: flowOf(null)
}

/** The open installation's permissions by name; empty while no store is open. */
@OptIn(ExperimentalCoroutinesApi::class)
fun AppGraph.permissions(): Flow<Map<String, Boolean>> = current.flatMapLatest { scope ->
  val storeUserId = scope?.account?.storeUserId
  accounts.accounts.map { accounts ->
    accounts.firstOrNull { it.storeUserId == storeUserId }?.installation?.permissions.orEmpty()
  }
}
