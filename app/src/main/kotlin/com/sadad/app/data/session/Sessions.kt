package com.sadad.app.data.session

import com.sadad.app.data.net.ApiException
import com.sadad.app.data.net.AuthApi
import com.sadad.app.data.net.AuthSession
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What a session needs from the graph that owns the open store. */
interface SessionHost {
  suspend fun open(account: Account)

  suspend fun close()

  suspend fun unsentCount(storeUserId: String): Int
}

/**
 * Sign-in, the in-memory access token and the sealed refresh token. One store is open at a time;
 * the access token never reaches disk and the refresh token is sealed under its store's AAD.
 */
class Sessions(
    private val accounts: Accounts,
    private val auth: AuthApi,
    private val installations: Installations,
    private val host: SessionHost,
    private val deviceLabel: String,
    /** Survives reinstall of the same signing key; null falls back to a random id. */
    private val installationSeed: () -> String? = { null },
    private val now: () -> Instant = Instant::now,
) {
  private val access = MutableStateFlow<AuthSession?>(null)
  private val syncAccess = ConcurrentHashMap<String, AuthSession>()
  private val mutex = Mutex()

  /** Signs in, creates the store's account on first sign-in, and opens its scope. */
  suspend fun signIn(username: String, password: String): AuthSession = mutex.withLock {
    val session = auth.signIn(username, password)
    val account = accounts.account(session.userId) ?: newAccount(session.userId, username)
    storeLocked(session)
    accounts.setActive(account.storeUserId)
    host.open(account)
    session
  }

  /**
   * A token with more than 60 seconds left, or a refreshed one. A refused refresh (400/401) signs
   * the store out; being offline keeps the token and the sealed refresh token.
   */
  suspend fun accessToken(): String? = mutex.withLock { validLocked()?.accessToken }

  /**
   * A worker requests this store's session without switching the store shown on screen. IO failures
   * propagate to sync; forceRefresh is used once after an authorization refusal.
   */
  suspend fun syncSession(storeUserId: String, forceRefresh: Boolean = false): AuthSession? =
      mutex.withLock {
        val refresh = accounts.refreshToken(storeUserId) ?: return@withLock null
        val cached = syncAccess[storeUserId]
        if (
            !forceRefresh &&
                cached != null &&
                Duration.between(now(), cached.expiresAt) >= REFRESH_MARGIN
        ) {
          return@withLock cached
        }
        try {
          val refreshed = auth.refresh(refresh)
          if (refreshed.userId != storeUserId) throw ApiException(401, "authorization_required")
          accounts.saveRefreshToken(storeUserId, refreshed.refreshToken)
          syncAccess[storeUserId] = refreshed
          if (accounts.file().activeStoreUserId == storeUserId) access.value = refreshed
          refreshed
        } catch (refused: ApiException) {
          if (refused.status != 400 && refused.status != 401) throw refused
          invalidateLocked(storeUserId)
          null
        }
      }

  /** Forget refused credentials only for the worker's store; never close another store's scope. */
  suspend fun invalidateForSync(storeUserId: String) = mutex.withLock {
    invalidateLocked(storeUserId)
  }

  private suspend fun invalidateLocked(storeUserId: String) {
    accounts.clearRefreshToken(storeUserId)
    syncAccess.remove(storeUserId)
    if (access.value?.userId == storeUserId) access.value = null
  }

  /** Changes the password, then refreshes and stores the new tokens. */
  suspend fun changePassword(current: String, new: String): Unit = mutex.withLock {
    val session = validLocked() ?: error("not signed in")
    auth.changePassword(session.accessToken, current, new)
    refreshLocked(session.refreshToken) ?: return@withLock
  }

  /** Best effort: an offline sign-out still clears the local tokens and leaves the store's data. */
  suspend fun signOut(): Unit = mutex.withLock {
    val session = access.value
    if (session != null) {
      try {
        auth.signOut(session.accessToken)
      } catch (_: IOException) {
        // Offline is fine: the local session is what must end.
      } catch (_: ApiException) {
        // The server already dropped it.
      }
    }
    val storeUserId = accounts.file().activeStoreUserId
    if (storeUserId != null) invalidateLocked(storeUserId)
    access.value = null
    host.close()
  }

  /** Closes the open scope and opens [storeUserId]; without a refresh token it stays at Login. */
  suspend fun switchTo(storeUserId: String): Unit = mutex.withLock {
    val account = accounts.account(storeUserId) ?: return@withLock
    access.value = null
    host.close()
    accounts.setActive(storeUserId)
    if (account.sealedRefreshToken != null) host.open(account)
  }

  /** The open store's queued outbox rows, for the sign-out and remove warnings. */
  suspend fun unsentCount(): Int {
    val storeUserId = accounts.file().activeStoreUserId ?: return 0
    return host.unsentCount(storeUserId)
  }

  /**
   * A revoked phone takes a fresh identity (new installation id and Keystore alias); the database
   * and its queued rows stay, and ticket 47's next pass enrolls the new key for approval.
   */
  suspend fun registerAgain(scope: StoreScope): Boolean = mutex.withLock {
    val account = accounts.account(scope.account.storeUserId) ?: return@withLock false
    if (account.installation?.status != "revoked") return@withLock false
    val installationId = UUID.randomUUID().toString()
    val keyAlias = nextAlias(account)
    keystore { installations.create(keyAlias, installationId) }
    val fresh =
        account.copy(installationId = installationId, keyAlias = keyAlias, installation = null)
    // Only the identity changes: a name or label saved meanwhile is kept.
    accounts.update(account.storeUserId) {
      it.copy(installationId = installationId, keyAlias = keyAlias, installation = null)
    }
    host.close()
    host.open(fresh)
    // Only once the new identity opens is the old key disposable; failing to delete it is not a
    // failed registration.
    runCatching { keystore { installations.delete(account.keyAlias) } }
    true
  }

  /** `installation-<storeUserId>` then `-1`, `-2`, … so the old alias is never reused. */
  private fun nextAlias(account: Account): String {
    val prefix = "installation-${account.storeUserId}"
    val previous = account.keyAlias.removePrefix("$prefix-").toIntOrNull() ?: 0
    return "$prefix-${previous + 1}"
  }

  /** Drops the in-memory token for a store the graph is removing. */
  internal fun leave(storeUserId: String) {
    syncAccess.remove(storeUserId)
    if (access.value?.userId == storeUserId) access.value = null
  }

  internal fun inMemorySession(storeUserId: String): AuthSession? =
      access.value?.takeIf { it.userId == storeUserId }

  private suspend fun newAccount(storeUserId: String, username: String): Account {
    val account =
        Account(
            storeUserId = storeUserId,
            username = username,
            installationId = DeviceIdentity.installationId(installationSeed()),
            keyAlias = "installation-$storeUserId",
            label = deviceLabel,
        )
    keystore { installations.create(account.keyAlias, account.installationId) }
    accounts.add(account)
    return account
  }

  /**
   * Keystore calls are slow and StrictMode flags key generation; sign-in is launched from the UI,
   * so every [installations] call here leaves the main thread.
   */
  private suspend fun <T> keystore(block: () -> T): T = withContext(Dispatchers.IO) { block() }

  private suspend fun storeLocked(session: AuthSession) {
    accounts.saveRefreshToken(session.userId, session.refreshToken)
    syncAccess[session.userId] = session
    access.value = session
  }

  private suspend fun validLocked(): AuthSession? {
    val session = access.value ?: restoreLocked() ?: return null
    if (Duration.between(now(), session.expiresAt) >= REFRESH_MARGIN) return session
    return try {
      refreshLocked(session.refreshToken)
    } catch (_: IOException) {
      session
    }
  }

  private suspend fun restoreLocked(): AuthSession? {
    val storeUserId = accounts.file().activeStoreUserId ?: return null
    val token = accounts.refreshToken(storeUserId) ?: return null
    return try {
      refreshLocked(token)
    } catch (_: IOException) {
      null
    }
  }

  private suspend fun refreshLocked(refreshToken: String): AuthSession? =
      try {
        auth.refresh(refreshToken).also { storeLocked(it) }
      } catch (refused: ApiException) {
        if (refused.status == 400 || refused.status == 401) {
          signedOutLocked()
          null
        } else {
          throw refused
        }
      }

  private suspend fun signedOutLocked() {
    val storeUserId = accounts.file().activeStoreUserId
    if (storeUserId != null) invalidateLocked(storeUserId)
    access.value = null
    host.close()
  }

  private companion object {
    val REFRESH_MARGIN: Duration = Duration.ofSeconds(60)
  }
}
