package com.sadad.app.data.crypto

import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class SealedTest {
  private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

  @Test
  fun sealThenOpenGivesThePlaintextBack() {
    val plaintext = "أمر بانتظار المزامنة".toByteArray(Charsets.UTF_8)
    val sealed = Sealed.seal(key, plaintext, "outbox:0b6f3c1e")
    assertArrayEquals(plaintext, Sealed.open(key, sealed, "outbox:0b6f3c1e"))
  }

  @Test
  fun everyFlippedByteIsRejected() {
    val sealed = Sealed.seal(key, "secret".toByteArray(Charsets.UTF_8), "outbox:0b6f3c1e")
    sealed.indices.forEach { index ->
      val tampered = sealed.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
      assertThrows(AEADBadTagException::class.java) {
        Sealed.open(key, tampered, "outbox:0b6f3c1e")
      }
    }
  }

  @Test
  fun anotherAadIsRejected() {
    val sealed = Sealed.seal(key, "secret".toByteArray(Charsets.UTF_8), "outbox:0b6f3c1e")
    assertThrows(AEADBadTagException::class.java) {
      Sealed.open(key, sealed, "refresh-token:store-1")
    }
  }

  @Test
  fun twoSealsOfTheSameTextDiffer() {
    val a = Sealed.seal(key, "secret".toByteArray(Charsets.UTF_8), "outbox:0b6f3c1e")
    val b = Sealed.seal(key, "secret".toByteArray(Charsets.UTF_8), "outbox:0b6f3c1e")
    assertFalse("a fresh IV each time", a.contentEquals(b))
  }
}
