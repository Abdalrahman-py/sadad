package com.sadad.ledger

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ProofTextTest {
  private val fixture = Fixtures.read("mobile/proof.json")

  private fun field(name: String) = fixture.getValue(name).jsonPrimitive.content

  @Test
  fun bodyDigestMatchesTheFixture() {
    assertEquals(field("bodySha256"), ProofText.sha256Hex(field("body")))
  }

  @Test
  fun proofTextMatchesTheFixtureByteForByte() {
    val text =
        ProofText.of(
            method = field("method"),
            route = field("route"),
            body = field("body"),
            time = field("time").toLong(),
            nonce = field("nonce"),
            installationId = field("installationId"),
        )
    assertEquals(field("proofText"), text)
  }

  @Test
  fun routeKeepsItsQueryString() {
    val text =
        ProofText.of(
            "get",
            "/v1/mobile/changes?after=1&limit=200",
            "",
            1_760_600_000_000,
            "5d0c2a9e-3f41-4b8a-a6e2-91c7d4f08b35",
            "8e4b1f72-6c3a-4d95-b0e8-2a7f5c19d463",
        )
    val lines = text.split("\n")
    assertEquals(7, lines.size)
    assertEquals("sadid-v3", lines[0])
    assertEquals("GET", lines[1])
    assertEquals("/v1/mobile/changes?after=1&limit=200", lines[2])
    // SHA-256 of the empty body.
    assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", lines[3])
    assertEquals("1760600000000", lines[4])
  }
}
