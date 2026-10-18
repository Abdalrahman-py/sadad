package com.sadad.app.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * `data-key.bin` is here but the Keystore wrap key that alone can open it is gone (Keystore reset,
 * partly cleared app data). The sealed rows are lost; there is no recovery path.
 */
class DataKeyLost(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The one AES-256-GCM data key per install. A Keystore key (`data-key-wrap`) seals a random 32-byte
 * key into `filesDir/data-key.bin` (IV ‖ ciphertext+tag) and `get` unwraps it once per process; the
 * first call creates both. Envelope encryption: one Keystore operation per install, then plain AES
 * for the outbox payloads and the refresh token.
 *
 * The constructor takes the key directly, for the tests' fake data key.
 */
class DataKey(val key: SecretKey) {
  companion object {
    const val WRAP_ALIAS = "data-key-wrap"
    const val FILE_NAME = "data-key.bin"

    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128
    private const val KEY_SIZE_BITS = 256
    private const val KEY_BYTES = 32

    @Volatile private var cached: DataKey? = null

    /**
     * The process-wide data key: created on the first call, unwrapped once, then held in memory.
     */
    fun get(context: Context): DataKey =
        cached ?: synchronized(this) { cached ?: load(context).also { cached = it } }

    /** Reads and unwraps `data-key.bin` again, ignoring the in-memory key. */
    private fun load(context: Context): DataKey {
      val file = File(context.filesDir, FILE_NAME)
      val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
      val hasWrapKey = keyStore.containsAlias(WRAP_ALIAS)
      if (file.exists()) {
        if (!hasWrapKey) throw DataKeyLost("$FILE_NAME is here but the wrap key is gone")
        val wrapKey = keyStore.getKey(WRAP_ALIAS, null) as SecretKey
        val bytes =
            try {
              unwrap(wrapKey, file.readBytes())
            } catch (bad: GeneralSecurityException) {
              throw DataKeyLost("$FILE_NAME does not open with the wrap key", bad)
            } catch (short: IllegalArgumentException) {
              throw DataKeyLost("$FILE_NAME is truncated", short)
            }
        return DataKey(SecretKeySpec(bytes, "AES"))
      }
      val wrapKey =
          if (hasWrapKey) keyStore.getKey(WRAP_ALIAS, null) as SecretKey else generateWrapKey()
      val bytes = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
      // Write then rename, so a power cut never leaves a half-written key file behind.
      val partial = File(context.filesDir, "$FILE_NAME.tmp")
      FileOutputStream(partial).use {
        it.write(wrap(wrapKey, bytes))
        it.fd.sync()
      }
      check(partial.renameTo(file)) { "could not move $FILE_NAME into place" }
      return DataKey(SecretKeySpec(bytes, "AES"))
    }

    /** Drops the in-memory key, so the next `get` reads `data-key.bin` again (the device test). */
    internal fun forget() {
      cached = null
    }

    private fun generateWrapKey(): SecretKey {
      val spec =
          KeyGenParameterSpec.Builder(
                  WRAP_ALIAS,
                  KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
              )
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .setKeySize(KEY_SIZE_BITS)
              .build()
      return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
          .apply { init(spec) }
          .generateKey()
    }

    /**
     * The Keystore picks the IV (randomized encryption stays required), so it is stored with it.
     */
    private fun wrap(wrapKey: SecretKey, bytes: ByteArray): ByteArray {
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.ENCRYPT_MODE, wrapKey)
      return cipher.iv + cipher.doFinal(bytes)
    }

    private fun unwrap(wrapKey: SecretKey, blob: ByteArray): ByteArray {
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, wrapKey, GCMParameterSpec(TAG_BITS, blob, 0, IV_SIZE))
      return cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE)
    }
  }
}
