package com.sadad.app.feature.lock

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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

  data class LockedOut(val remaining: Long) : PinResult
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
          resetAttempts(it)
        }
        Unit
      }

  suspend fun clearPin() {
    data.edit {
      it.remove(SALT)
      it.remove(HASH)
      resetAttempts(it)
    }
  }

  suspend fun hasPin(): Boolean = !data.data.first()[HASH].isNullOrEmpty()

  suspend fun verifyPin(pin: String): PinResult =
      withContext(Dispatchers.IO) {
        var result: PinResult = PinResult.Wrong(5)
        data.edit { saved ->
          val remaining = remaining(saved)
          if (remaining > 0) {
            result = PinResult.LockedOut(remaining)
            return@edit
          }
          val salt = saved[SALT]
          val expected = saved[HASH]
          if (salt == null || expected == null) return@edit
          if (
              MessageDigest.isEqual(Base64Url.decode(expected), derive(pin, Base64Url.decode(salt)))
          ) {
            resetAttempts(saved)
            result = PinResult.Ok
          } else {
            val attempts = (saved[FAILURES] ?: 0) + 1
            if (attempts < 5) {
              saved[FAILURES] = attempts
              result = PinResult.Wrong(5 - attempts)
            } else {
              val level = ((saved[LEVEL] ?: 0) + 1).coerceAtMost(6)
              val delay = minOf(900_000L, 30_000L shl (level - 1))
              saved[LEVEL] = level
              saved[FAILURES] = 0
              saved[DELAY] = delay
              saved[BOOT] = bootCount()
              saved[DEADLINE] = elapsedRealtime() + delay
              result = PinResult.LockedOut(delay)
            }
          }
        }
        result
      }

  suspend fun lockout(): PinResult.LockedOut? {
    var result: PinResult.LockedOut? = null
    data.edit { saved ->
      val remaining = remaining(saved)
      if (remaining > 0) result = PinResult.LockedOut(remaining)
    }
    return result
  }

  private fun remaining(saved: MutablePreferences): Long {
    val deadline = saved[DEADLINE] ?: return 0L
    val now = elapsedRealtime()
    val boot = bootCount()
    if (saved[BOOT] != boot) {
      val delay = saved[DELAY] ?: 0L
      saved[BOOT] = boot
      saved[DEADLINE] = now + delay
      return delay
    }
    val remaining = (deadline - now).coerceAtLeast(0)
    if (remaining == 0L) {
      saved.remove(DEADLINE)
      saved.remove(DELAY)
      saved.remove(BOOT)
    }
    return remaining
  }

  private fun resetAttempts(saved: MutablePreferences) {
    saved.remove(FAILURES)
    saved.remove(LEVEL)
    saved.remove(DEADLINE)
    saved.remove(DELAY)
    saved.remove(BOOT)
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
    val FAILURES = intPreferencesKey("pin_failed_attempts")
    val LEVEL = intPreferencesKey("pin_lock_level")
    val BOOT = intPreferencesKey("pin_boot")
    val DEADLINE = longPreferencesKey("pin_deadline")
    val DELAY = longPreferencesKey("pin_delay")
  }
}
