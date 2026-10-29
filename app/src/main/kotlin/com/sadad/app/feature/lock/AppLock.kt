package com.sadad.app.feature.lock

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sadad.app.data.crypto.Base64Url
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

sealed interface PinResult {
  data object Ok : PinResult

  data class Wrong(val attemptsLeft: Int) : PinResult

  data class LockedOut(val remaining: Long) : PinResult
}

/** The phone-wide PIN verifier. Plain PINs are never written to disk. */
class AppLock(
    produceFile: () -> File,
    scope: CoroutineScope,
    private val elapsedRealtime: () -> Long,
    private val bootCount: () -> Int,
) {
  constructor(
      file: File,
      scope: CoroutineScope,
      elapsedRealtime: () -> Long,
      bootCount: () -> Int,
  ) : this({ file }, scope, elapsedRealtime, bootCount)

  // produceFile runs on the DataStore's thread at the first read, never on the caller's.
  private val data = PreferenceDataStoreFactory.create(scope = scope, produceFile = produceFile)
  private val mutableLocked = MutableStateFlow(true)
  private val skip = AtomicBoolean(false)
  private var leftAt: Long? = null

  /** The lock is on and the PIN is set; Java defaulted [lockEnabled] to true. */
  val lockEnabled: Flow<Boolean> = data.data.map { it[LOCK] ?: true }
  val biometricEnabled: Flow<Boolean> = data.data.map { it[BIOMETRIC] ?: false }
  val pinSet: Flow<Boolean> = data.data.map { !it[HASH].isNullOrEmpty() }

  /** True from process start until the first unlock, and again after a long absence. */
  val locked: StateFlow<Boolean> = mutableLocked.asStateFlow()

  fun markUnlocked() {
    mutableLocked.value = false
  }

  /** Call before launching speech input or a system picker, so coming back does not lock. */
  fun skipNextLock() = skip.set(true)

  fun appStopped() {
    if (!skip.get()) leftAt = elapsedRealtime()
  }

  fun appStarted() {
    val left = leftAt
    leftAt = null
    val skipped = skip.getAndSet(false)
    if (!skipped && left != null && elapsedRealtime() - left > AWAY_MS) mutableLocked.value = true
  }

  suspend fun setLockEnabled(on: Boolean) {
    data.edit {
      it[LOCK] = on
      if (!on) it[BIOMETRIC] = false
    }
  }

  suspend fun setBiometricEnabled(on: Boolean) {
    data.edit { it[BIOMETRIC] = on }
  }

  suspend fun setPin(pin: String) =
      withContext(Dispatchers.IO) {
        require(pin.length == 4 && pin.all(Char::isDigit))
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val hash = derive(pin, salt)
        data.edit {
          it[SALT] = Base64Url.encode(salt)
          it[HASH] = Base64Url.encode(hash)
          it[LOCK] = true
          resetAttempts(it)
        }
        Unit
      }

  suspend fun clearPin() {
    data.edit {
      it.remove(SALT)
      it.remove(HASH)
      it.remove(LOCK)
      it.remove(BIOMETRIC)
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

  companion object {
    private const val AWAY_MS = 1_200L

    @Volatile private var instance: AppLock? = null

    /** One per process; the boot count is read on the DataStore's thread, never on main. */
    fun get(context: Context): AppLock =
        instance
            ?: synchronized(this) {
              instance
                  ?: AppLock(
                          { File(context.applicationContext.filesDir, "lock.preferences_pb") },
                          CoroutineScope(SupervisorJob() + Dispatchers.IO),
                          SystemClock::elapsedRealtime,
                          {
                            Settings.Global.getInt(
                                context.applicationContext.contentResolver,
                                Settings.Global.BOOT_COUNT,
                                -1,
                            )
                          },
                      )
                      .also { instance = it }
            }

    private val LOCK = booleanPreferencesKey("lock_enabled")
    private val BIOMETRIC = booleanPreferencesKey("biometric_enabled")
    private val SALT = stringPreferencesKey("pin_salt")
    private val HASH = stringPreferencesKey("pin_hash")
    private val FAILURES = intPreferencesKey("pin_failed_attempts")
    private val LEVEL = intPreferencesKey("pin_lock_level")
    private val BOOT = intPreferencesKey("pin_boot")
    private val DEADLINE = longPreferencesKey("pin_deadline")
    private val DELAY = longPreferencesKey("pin_delay")
  }
}
