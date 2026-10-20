package com.sadad.app.data.recovery

import com.sadad.app.testing.STORE_A
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecoveryFileTest {
  private val passphrase = "correct horse battery".toCharArray()

  private val bundle = buildJsonObject {
    put("storeUserId", STORE_A)
    put("username", "store-$STORE_A")
    put(
        "commands",
        buildJsonArray {
          add(
              buildJsonObject {
                put("operationId", "6f1c2b3a-0d4e-4f5a-8b6c-7d8e9f0a1b2c")
                put("type", "contact.create")
              }
          )
        },
    )
  }

  @Test
  fun aSealedBundleOpensWithTheSameContents() {
    val file = RecoveryFile.seal(passphrase.copyOf(), bundle)

    assertEquals(bundle, RecoveryFile.open(file, passphrase.copyOf()))
  }

  @Test
  fun aWrongPassphraseFailsWithTheRecoveryMessage() {
    val file = RecoveryFile.seal(passphrase.copyOf(), bundle)

    val failure =
        assertThrows(IllegalArgumentException::class.java) {
          RecoveryFile.open(file, "wrong passphrase!".toCharArray())
        }

    assertEquals("تعذر فتح نسخة الاسترداد: عبارة المرور خاطئة أو الملف معدّل.", failure.message)
  }

  @Test
  fun aFlippedByteFails() {
    val sealed = RecoveryFile.seal(passphrase.copyOf(), bundle)
    val marker = "\"data\":\"".toByteArray()
    val dataStart = indexOf(sealed, marker) + marker.size
    val flipped = sealed.copyOf()
    flipped[dataStart + 8] =
        if (flipped[dataStart + 8] == 'A'.code.toByte()) 'B'.code.toByte() else 'A'.code.toByte()

    assertThrows(IllegalArgumentException::class.java) {
      RecoveryFile.open(flipped, passphrase.copyOf())
    }
  }

  @Test
  fun aPassphraseOfElevenCharactersIsRefused() {
    assertThrows(IllegalArgumentException::class.java) {
      RecoveryFile.seal("12345678901".toCharArray(), bundle)
    }
  }

  @Test
  fun theEnvelopeNamesTheFormatTheKdfAndItsIterations() {
    val envelope =
        Json.parseToJsonElement(
                String(RecoveryFile.seal(passphrase.copyOf(), bundle), Charsets.UTF_8)
            )
            .jsonObject

    assertEquals("sadid-recovery-v1", envelope.getString("format"))
    assertEquals("PBKDF2-HMAC-SHA256", envelope.getString("kdf"))
    assertEquals(600_000, envelope.getValue("iterations").jsonPrimitive.content.toInt())
  }

  @Test
  fun aFileOverEightMegabytesIsRefused() {
    assertThrows(IllegalArgumentException::class.java) {
      RecoveryFile.open(ByteArray(8 * 1024 * 1024 + 1), passphrase.copyOf())
    }
  }

  @Test
  fun aPassphraseOfExactlyTwelveCharactersIsAccepted() {
    val file = RecoveryFile.seal("123456789012".toCharArray(), bundle)

    assertEquals(bundle, RecoveryFile.open(file, "123456789012".toCharArray()))
  }

  @Test
  fun anEnvelopeWithAnotherIterationCountFormatOrKdfIsRefusedBeforeTheKdfRuns() {
    val sealed = String(RecoveryFile.seal(passphrase.copyOf(), bundle), Charsets.UTF_8)
    for (tampered in
        listOf(
            sealed.replace("\"iterations\":600000", "\"iterations\":1"),
            sealed.replace("\"iterations\":600000", "\"iterations\":2000000000"),
            sealed.replace("sadid-recovery-v1", "sadid-recovery-v0"),
            sealed.replace("PBKDF2-HMAC-SHA256", "PBKDF2-HMAC-SHA1"),
        )) {
      val failure =
          assertThrows(IllegalArgumentException::class.java) {
            RecoveryFile.open(tampered.toByteArray(Charsets.UTF_8), passphrase.copyOf())
          }

      assertEquals("صيغة استرداد غير مدعومة.", failure.message)
    }
  }

  @Test
  fun anEnvelopeWhoseFieldsAreNotTextFailsWithTheRecoveryMessageNotATechnicalOne() {
    val broken = """{"format":{},"kdf":[],"iterations":null}"""

    val failure =
        assertThrows(IllegalArgumentException::class.java) {
          RecoveryFile.open(broken.toByteArray(Charsets.UTF_8), passphrase.copyOf())
        }

    assertEquals("تعذر فتح نسخة الاسترداد: عبارة المرور خاطئة أو الملف معدّل.", failure.message)
  }

  @Test
  fun aShortSaltOrIvFailsWithTheRecoveryMessage() {
    val sealed = String(RecoveryFile.seal(passphrase.copyOf(), bundle), Charsets.UTF_8)
    val salt = Regex("\"salt\":\"([^\"]*)\"").find(sealed)!!.groupValues[1]
    val shortSalt = sealed.replace(salt, salt.take(4))

    assertThrows(IllegalArgumentException::class.java) {
      RecoveryFile.open(shortSalt.toByteArray(Charsets.UTF_8), passphrase.copyOf())
    }
  }

  private fun JsonObject.getString(name: String): String = (getValue(name) as JsonPrimitive).content

  private fun indexOf(bytes: ByteArray, needle: ByteArray): Int {
    for (start in 0..bytes.size - needle.size) {
      if (needle.indices.all { bytes[start + it] == needle[it] }) return start
    }
    error("marker not found")
  }
}
