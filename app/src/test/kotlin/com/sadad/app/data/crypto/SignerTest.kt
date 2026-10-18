package com.sadad.app.data.crypto

import java.security.Signature
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignerTest {
  private val signer = SoftwareSigner("8e4b1f72-6c3a-4d95-b0e8-2a7f5c19d463")

  @Test
  fun signReturnsSixtyFourRawBytes() {
    assertEquals(64, signer.sign("تحقق".toByteArray(Charsets.UTF_8)).size)
  }

  @Test
  fun theSignatureVerifiesWithThePublicKey() {
    val message = "sadid-v3\nGET\n/v1/mobile/changes\n".toByteArray(Charsets.UTF_8)
    val verifier = Signature.getInstance("SHA256withECDSA")
    verifier.initVerify(signer.publicKey.toPublicKey())
    verifier.update(message)
    assertTrue(
        "DER form of the signature did not verify",
        verifier.verify(Der.toDer(signer.sign(message))),
    )
  }

  @Test
  fun derRoundTripsEvenWhenRorSIsShort() {
    repeat(1_000) { index ->
      val raw = signer.sign("message-$index".toByteArray(Charsets.UTF_8))
      assertEquals(64, raw.size)
      assertArrayEquals(raw, Der.toRaw(Der.toDer(raw)))
    }
  }
}
