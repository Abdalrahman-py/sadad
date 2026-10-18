package com.sadad.app.data.crypto

import com.sadad.ledger.ProofText
import java.io.File
import java.security.Signature
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared contract fixture: the exact proof the `api` verifies, byte for byte. */
class ProofFixtureTest {
  private val fixture =
      Json.parseToJsonElement(File(fixturesDirectory(), "mobile/proof.json").readText()).jsonObject

  private fun field(name: String) = fixture.getValue(name).jsonPrimitive.content

  @Test
  fun theFixtureSignatureVerifiesWithItsPublicKey() {
    val jwk = fixture.getValue("publicKey").jsonObject
    val publicKey =
        PublicJwk(
                x = jwk.getValue("x").jsonPrimitive.content,
                y = jwk.getValue("y").jsonPrimitive.content,
            )
            .toPublicKey()
    val raw = Base64Url.decode(field("signature"))
    assertEquals("raw r||s is 64 bytes", 64, raw.size)
    val verifier = Signature.getInstance("SHA256withECDSA")
    verifier.initVerify(publicKey)
    verifier.update(field("proofText").toByteArray(Charsets.UTF_8))
    assertTrue("the fixture signature did not verify", verifier.verify(Der.toDer(raw)))
  }

  @Test
  fun proofTextMatchesTheFixtureByteForByte() {
    assertEquals(
        field("proofText"),
        ProofText.of(
            method = field("method"),
            route = field("route"),
            body = field("body"),
            time = field("time").toLong(),
            nonce = field("nonce"),
            installationId = field("installationId"),
        ),
    )
  }

  private fun fixturesDirectory(): File {
    val path = System.getProperty("sadad.fixtures") ?: error("sadad.fixtures is not set")
    return File(path).also { check(it.isDirectory) { "missing fixtures directory $it" } }
  }
}
