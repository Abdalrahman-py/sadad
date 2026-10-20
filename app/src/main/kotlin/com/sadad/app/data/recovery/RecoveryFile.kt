package com.sadad.app.data.recovery

import com.sadad.app.data.crypto.Base64Url
import java.nio.charset.StandardCharsets.UTF_8
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The passphrase-sealed recovery copy: PBKDF2-HMAC-SHA256 (600,000 iterations) then AES-256-GCM, in
 * the Java app's `sadid-recovery-v1` envelope. Neither the installation key nor a session token is
 * part of the bundle; the passphrase is never written anywhere.
 */
object RecoveryFile {
  private const val FORMAT = "sadid-recovery-v1"
  private const val KDF = "PBKDF2-HMAC-SHA256"
  private const val ITERATIONS = 600_000
  private const val MAX_SIZE = 8 * 1024 * 1024
  private const val SALT_SIZE = 16
  private const val IV_SIZE = 12
  private const val TAG_BITS = 128
  private const val MIN_PASSPHRASE = 12
  private const val TRANSFORMATION = "AES/GCM/NoPadding"

  const val WRONG_PASSPHRASE = "تعذر فتح نسخة الاسترداد: عبارة المرور خاطئة أو الملف معدّل."

  /** Seals [bundle] and returns the JSON envelope; clears [passphrase] before returning. */
  fun seal(passphrase: CharArray, bundle: JsonObject): ByteArray =
      try {
        require(passphrase.size >= MIN_PASSPHRASE) {
          "استخدم عبارة مرور من ١٢ حرفًا على الأقل واحتفظ بها خارج الهاتف."
        }
        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val key = derive(passphrase, salt)
        try {
          val cipher = Cipher.getInstance(TRANSFORMATION)
          cipher.init(
              Cipher.ENCRYPT_MODE,
              SecretKeySpec(key, "AES"),
              GCMParameterSpec(TAG_BITS, iv),
          )
          cipher.updateAAD(FORMAT.toByteArray(UTF_8))
          val data = cipher.doFinal(bundle.toString().toByteArray(UTF_8))
          val envelope = buildJsonObject {
            put("format", FORMAT)
            put("kdf", KDF)
            put("iterations", ITERATIONS)
            put("salt", Base64Url.encode(salt))
            put("iv", Base64Url.encode(iv))
            put("data", Base64Url.encode(data))
          }
          val file = envelope.toString().toByteArray(UTF_8)
          require(file.size <= MAX_SIZE) {
            "نسخة الاسترداد أكبر من الحد المسموح (8 MB)؛ راجع الإدارة."
          }
          file
        } finally {
          key.fill(0)
        }
      } finally {
        passphrase.fill('\u0000')
      }

  /**
   * Opens [file]; the same message covers a wrong passphrase, a flipped byte and a broken envelope.
   */
  fun open(file: ByteArray, passphrase: CharArray): JsonObject =
      try {
        require(file.size <= MAX_SIZE) { "ملف الاسترداد أكبر من الحد المسموح." }
        val envelope =
            try {
              Json.parseToJsonElement(file.toString(UTF_8)).jsonObject
            } catch (_: RuntimeException) {
              throw IllegalArgumentException(WRONG_PASSPHRASE)
            }
        if (
            envelope["format"]?.jsonPrimitive?.content != FORMAT ||
                envelope["kdf"]?.jsonPrimitive?.content != KDF ||
                envelope["iterations"]?.jsonPrimitive?.content != ITERATIONS.toString()
        ) {
          throw IllegalArgumentException("صيغة استرداد غير مدعومة.")
        }
        val salt =
            try {
              Base64Url.decode(envelope.getValue("salt").jsonPrimitive.content)
            } catch (_: RuntimeException) {
              throw IllegalArgumentException(WRONG_PASSPHRASE)
            }
        val iv =
            try {
              Base64Url.decode(envelope.getValue("iv").jsonPrimitive.content)
            } catch (_: RuntimeException) {
              throw IllegalArgumentException(WRONG_PASSPHRASE)
            }
        if (salt.size != SALT_SIZE || iv.size != IV_SIZE) {
          throw IllegalArgumentException("ملف غير صالح.")
        }
        val key = derive(passphrase, salt)
        try {
          val data =
              try {
                Base64Url.decode(envelope.getValue("data").jsonPrimitive.content)
              } catch (_: RuntimeException) {
                throw IllegalArgumentException(WRONG_PASSPHRASE)
              }
          val cipher = Cipher.getInstance(TRANSFORMATION)
          cipher.init(
              Cipher.DECRYPT_MODE,
              SecretKeySpec(key, "AES"),
              GCMParameterSpec(TAG_BITS, iv),
          )
          cipher.updateAAD(FORMAT.toByteArray(UTF_8))
          val opened = cipher.doFinal(data).toString(UTF_8)
          try {
            Json.parseToJsonElement(opened).jsonObject
          } catch (_: RuntimeException) {
            throw IllegalArgumentException(WRONG_PASSPHRASE)
          }
        } catch (_: GeneralSecurityException) {
          throw IllegalArgumentException(WRONG_PASSPHRASE)
        } finally {
          key.fill(0)
        }
      } finally {
        passphrase.fill('\u0000')
      }

  private fun derive(passphrase: CharArray, salt: ByteArray): ByteArray {
    val spec = PBEKeySpec(passphrase, salt, ITERATIONS, 256)
    return try {
      SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
      spec.clearPassword()
    }
  }
}
