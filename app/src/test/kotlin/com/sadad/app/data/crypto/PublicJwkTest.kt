package com.sadad.app.data.crypto

import java.security.interfaces.ECPublicKey
import org.junit.Assert.assertEquals
import org.junit.Test

class PublicJwkTest {
  @Test
  fun coordinatesAreUnpaddedBase64UrlOf32Bytes() {
    val keyPair = ecP256KeyPair()
    val jwk = PublicJwk.of(keyPair.public as ECPublicKey)

    assertEquals(43, jwk.x.length)
    assertEquals(43, jwk.y.length)
    assertEquals(32, Base64Url.decode(jwk.x).size)
    assertEquals(32, Base64Url.decode(jwk.y).size)
    assertEquals(keyPair.public, jwk.toPublicKey())
  }
}
