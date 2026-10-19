package com.sadad.app.feature.lock

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sadad.app.data.crypto.Base64Url
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

sealed interface PinResult {
  data object Ok : PinResult

  data class Wrong(val attemptsLeft: Int) : PinResult
}

/** The phone-wide PIN verifier. Plain PINs are never written to disk. */
class AppLock(
    file: File,
    scope: CoroutineScope,
    private val elapsedRealtime: () -> Long,
    private val bootCount: () -> Int,
) {
  private val data = PreferenceDataStoreFactory.create(scope = scope) { file }

  suspend fun setPin(pin: String) =
      withContext(Dispatchers.IO) {
        require(pin.length in 4..12 && pin.all(Char::isDigit))
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val hash = derive(pin, salt)
        data.edit {
          it[SALT] = Base64Url.encode(salt)
          it[HASH] = Base64Url.encode(hash)
        }
        Unit
      }

  suspend fun clearPin() {
    data.edit {
      it.remove(SALT)
      it.remove(HASH)
    }
  }

  suspend fun hasPin(): Boolean = !data.data.first()[HASH].isNullOrEmpty()

  suspend fun verifyPin(pin: String): PinResult =
      withContext(Dispatchers.IO) {
        val saved = data.data.first()
        val salt = saved[SALT]
        val expected = saved[HASH]
        if (
            salt != null &&
                expected != null &&
                MessageDigest.isEqual(
                    Base64Url.decode(expected),
                    derive(pin, Base64Url.decode(salt)),
                )
        ) {
          PinResult.Ok
        } else {
          PinResult.Wrong(4)
        }
      }

  private fun derive(pin: String, salt: ByteArray): ByteArray {
    val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
    return try {
      SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
      spec.clearPassword()
    }
  }

  private companion object {
    val SALT = stringPreferencesKey("pin_salt")
    val HASH = stringPreferencesKey("pin_hash")
  }
}
