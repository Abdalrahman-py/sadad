package com.sadad.app.data.crypto

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM for the small payloads that must not be plaintext on disk (outbox commands, the
 * refresh token). One 12-byte random IV per seal, prepended to the ciphertext, and a 128-bit tag
 * the open verifies: `open` throws `AEADBadTagException` on a flipped byte or a different AAD.
 */
object Sealed {
  private const val IV_SIZE = 12
  private const val TAG_BITS = 128
  private const val TRANSFORMATION = "AES/GCM/NoPadding"

  /** The `aad` names the use (`"outbox:<operationId>"`, `"refresh-token:<storeUserId>"`). */
  fun seal(key: SecretKey, plaintext: ByteArray, aad: String): ByteArray {
    val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
    cipher.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
    return iv + cipher.doFinal(plaintext)
  }

  fun open(key: SecretKey, sealed: ByteArray, aad: String): ByteArray {
    require(sealed.size > IV_SIZE) { "sealed payload is too short" }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_SIZE))
    cipher.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
    return cipher.doFinal(sealed, IV_SIZE, sealed.size - IV_SIZE)
  }
}
