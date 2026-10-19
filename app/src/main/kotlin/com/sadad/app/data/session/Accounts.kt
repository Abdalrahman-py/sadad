package com.sadad.app.data.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sadad.app.data.crypto.Base64Url
import com.sadad.app.data.crypto.Sealed
import com.sadad.ledger.LedgerJson
import java.io.File
import java.security.GeneralSecurityException
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/** The store's server installation, as the last enroll or session refresh saw it. */
@Serializable
data class StoredInstallation(
    val status: String,
    val generation: Int,
    val permissions: Map<String, Boolean> = emptyMap(),
)

/** One store the phone remembers: its own installation key, database and sealed refresh token. */
@Serializable
data class Account(
    val storeUserId: String,
    val username: String,
    val installationId: String,
    val keyAlias: String,
    val label: String,
    val staffName: String = "",
    val storeName: String = "",
    val ownerName: String = "",
    val installation: StoredInstallation? = null,
    val sealedRefreshToken: String? = null,
)

/** The one JSON value in `accounts.preferences_pb`. */
@Serializable
data class AccountsFile(
    val accounts: List<Account> = emptyList(),
    val activeStoreUserId: String? = null,
    val welcomeShown: Boolean = false,
    val notificationsAsked: Boolean = false,
)

/**
 * The phone's store accounts and the two phone-wide flags, in one DataStore Preferences file. Every
 * change is one read-modify-write of the JSON value, so two updates never interleave.
 */
class Accounts
private constructor(
    private val store: DataStore<Preferences>,
    private val dataKey: SecretKey,
) {
  val state: Flow<AccountsFile> = store.data.map { decode(it[KEY]) }

  val accounts: Flow<List<Account>> = state.map { it.accounts }

  val welcomeShown: Flow<Boolean> = state.map { it.welcomeShown }

  val notificationsAsked: Flow<Boolean> = state.map { it.notificationsAsked }

  suspend fun file(): AccountsFile = state.first()

  suspend fun account(storeUserId: String): Account? =
      file().accounts.firstOrNull { it.storeUserId == storeUserId }

  /** Adds a new account, or replaces the stored one with the same store user. */
  suspend fun add(account: Account) = mutate { file ->
    file.copy(
        accounts =
            if (file.accounts.any { it.storeUserId == account.storeUserId })
                file.accounts.map { if (it.storeUserId == account.storeUserId) account else it }
            else file.accounts + account
    )
  }

  /** One write; a missing store user leaves the file as it is. */
  suspend fun update(storeUserId: String, block: (Account) -> Account) = mutate { file ->
    file.copy(accounts = file.accounts.map { if (it.storeUserId == storeUserId) block(it) else it })
  }

  suspend fun setActive(storeUserId: String) = mutate { it.copy(activeStoreUserId = storeUserId) }

  suspend fun setWelcomeShown() = mutate { it.copy(welcomeShown = true) }

  suspend fun setNotificationsAsked() = mutate { it.copy(notificationsAsked = true) }

  /** Seals the refresh token with the process data key; the file never holds it in plaintext. */
  suspend fun saveRefreshToken(storeUserId: String, token: String) {
    val sealed =
        Base64Url.encode(
            Sealed.seal(dataKey, token.toByteArray(Charsets.UTF_8), refreshTokenAad(storeUserId))
        )
    update(storeUserId) { it.copy(sealedRefreshToken = sealed) }
  }

  suspend fun refreshToken(storeUserId: String): String? {
    val sealed = account(storeUserId)?.sealedRefreshToken ?: return null
    return try {
      Sealed.open(dataKey, Base64Url.decode(sealed), refreshTokenAad(storeUserId))
          .toString(Charsets.UTF_8)
    } catch (_: GeneralSecurityException) {
      null
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  suspend fun clearRefreshToken(storeUserId: String) =
      update(storeUserId) { it.copy(sealedRefreshToken = null) }

  suspend fun remove(storeUserId: String) = mutate { file ->
    val left = file.accounts.filterNot { it.storeUserId == storeUserId }
    file.copy(
        accounts = left,
        activeStoreUserId =
            if (file.activeStoreUserId == storeUserId) left.firstOrNull()?.storeUserId
            else file.activeStoreUserId,
    )
  }

  private suspend fun mutate(block: (AccountsFile) -> AccountsFile) {
    store.edit { preferences ->
      val current = preferences[KEY]?.let(::decode) ?: AccountsFile()
      preferences[KEY] = encode(block(current))
    }
  }

  private fun decode(text: String?): AccountsFile =
      text?.let { LedgerJson.decodeFromString(AccountsFile.serializer(), it) } ?: AccountsFile()

  private fun encode(file: AccountsFile): String =
      LedgerJson.encodeToString(AccountsFile.serializer(), file)

  private fun refreshTokenAad(storeUserId: String): String = "refresh-token:$storeUserId"

  companion object {
    const val FILE_NAME = "accounts.preferences_pb"

    private val KEY = stringPreferencesKey("accounts")

    fun create(scope: CoroutineScope, file: File, dataKey: SecretKey): Accounts =
        Accounts(PreferenceDataStoreFactory.create(scope = scope) { file }, dataKey)
  }
}
