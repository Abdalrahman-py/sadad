package com.sadad.app.data.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sadad.app.data.net.Lease
import com.sadad.app.data.net.LeasePayload
import com.sadad.app.data.net.LeaseVerifier
import com.sadad.app.data.session.Account
import com.sadad.ledger.LedgerJson
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

/** One store's signed lease. Only verified bytes are cached for the synchronous write guard. */
class LeaseStore(
    file: File,
    scope: CoroutineScope,
    private val verifier: LeaseVerifier,
    private val bootCount: () -> Int,
    private val now: () -> Long = System::currentTimeMillis,
) {
  private val data = PreferenceDataStoreFactory.create(scope = scope) { file }
  @Volatile private var verified: Record? = null

  suspend fun load(account: Account) {
    val raw = data.data.first()[KEY]
    val record =
        try {
          raw?.let { LedgerJson.decodeFromString(Record.serializer(), it) }
        } catch (_: IllegalArgumentException) {
          null
        }
    verified = record?.takeIf {
      verifier.verify(it.lease, account.installationId, account.storeUserId)
    }
  }

  suspend fun save(account: Account, lease: Lease) {
    if (!verifier.verify(lease, account.installationId, account.storeUserId)) {
      clear()
      return
    }
    val record = Record(lease, bootCount())
    data.edit { it[KEY] = LedgerJson.encodeToString(Record.serializer(), record) }
    verified = record
  }

  suspend fun clear() {
    verified = null
    data.edit { it.remove(KEY) }
  }

  fun allows(account: Account): Boolean {
    val record = verified ?: return false
    val payload = record.lease.payload
    val boot = bootCount()
    val at = now()
    return boot >= 0 &&
        record.boot == boot &&
        payload.userId == account.storeUserId &&
        payload.installationId == account.installationId &&
        account.installation?.status == "active" &&
        payload.generation == account.installation.generation &&
        usableFrom(payload) <= at &&
        at < usableUntil(payload) &&
        payload.allowed.containsAll(OFFLINE_TYPES)
  }

  fun needsRenewal(account: Account): Boolean {
    if (!allows(account)) return true
    val payload = verified?.lease?.payload ?: return true
    return usableUntil(payload) - now() < RENEW_BEFORE
  }

  /**
   * When the cached lease for [account] ends, or `null` when the cached lease belongs to another
   * installation. Read-only: the screen names the time the store may keep recording offline.
   */
  fun expiresAt(account: Account): Long? {
    val payload = verified?.lease?.payload ?: return null
    val installation = account.installation ?: return null
    val mine =
        payload.userId == account.storeUserId &&
            payload.installationId == account.installationId &&
            payload.generation == installation.generation &&
            installation.status == "active"
    return if (mine) payload.expiresAt else null
  }

  @Serializable private data class Record(val lease: Lease, val boot: Int)

  /**
   * The window this lease is usable in. `issuedAt` is the server's clock and [now] the device's, so
   * a phone a few minutes behind must not read its own lease as not-yet-issued: the same ±5
   * minutes `proof.ts` already allows between a device and the server.
   */
  private fun usableFrom(payload: LeasePayload): Long = payload.issuedAt - CLOCK_SKEW_MS

  private fun usableUntil(payload: LeasePayload): Long = payload.expiresAt

  private companion object {
    const val CLOCK_SKEW_MS = 300_000L
    val KEY = stringPreferencesKey("lease")
    const val RENEW_BEFORE = 86400000L
    val OFFLINE_TYPES = listOf("contact.create", "debt.create", "payment.create")
  }
}
