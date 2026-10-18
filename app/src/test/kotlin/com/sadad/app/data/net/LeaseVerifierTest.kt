package com.sadad.app.data.net

import com.sadad.app.BuildConfig
import com.sadad.app.data.crypto.Base64Url
import com.sadad.app.data.crypto.SoftwareSigner
import com.sadad.ledger.LedgerJson
import java.net.URI
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LeaseVerifierTest {
  @Test
  fun acceptsOnlyThePinnedSignatureMatchingIdentityProjectAndSevenDayDuration() {
    val server = SoftwareSigner("server")
    val verifier = LeaseVerifier(server.publicKey.x, server.publicKey.y)
    val payload = buildJsonObject {
      put("schemaVersion", 1)
      put("projectRef", URI(BuildConfig.SUPABASE_URL).host.substringBefore('.'))
      put("storeId", 7)
      put("userId", "user-a")
      put("installationId", INSTALLATION_ID)
      put("generation", 1)
      put("issuedAt", 1800000000000L)
      put("expiresAt", 1800604800000L)
      put(
          "allowed",
          buildJsonArray {
            add("contact.create")
            add("debt.create")
            add("payment.create")
          },
      )
    }
    fun signed(json: JsonObject): Lease {
      val bytes = json.toString().toByteArray()
      return Lease(
          LedgerJson.decodeFromJsonElement(LeasePayload.serializer(), json),
          Base64Url.encode(bytes),
          Base64Url.encode(server.sign(bytes)),
      )
    }
    val valid = signed(payload)
    assertTrue(verifier.verify(valid, INSTALLATION_ID, "user-a"))
    val other = SoftwareSigner("other-server")
    assertFalse(
        LeaseVerifier(other.publicKey.x, other.publicKey.y).verify(valid, INSTALLATION_ID, "user-a")
    )
    assertFalse(LeaseVerifier("", "").verify(valid, INSTALLATION_ID, "user-a"))
    assertFalse(LeaseVerifier("bad", "bad").verify(valid, INSTALLATION_ID, "user-a"))
    assertFalse(verifier.verify(valid, "another-installation", "user-a"))
    assertFalse(verifier.verify(valid, INSTALLATION_ID, "another-user"))
    for ((field, value) in
        listOf(
            "expiresAt" to JsonPrimitive(1800691200000L), // Eight days.
            "projectRef" to JsonPrimitive("another-project"),
            "schemaVersion" to JsonPrimitive(2),
        )) {
      assertFalse(
          field,
          verifier.verify(
              signed(JsonObject(payload + (field to value))),
              INSTALLATION_ID,
              "user-a",
          ),
      )
    }
    assertFalse(
        verifier.verify(
            valid.copy(payload = valid.payload.copy(storeId = 999)),
            INSTALLATION_ID,
            "user-a",
        )
    )
    assertFalse(verifier.verify(valid.copy(raw = "malformed!"), INSTALLATION_ID, "user-a"))
    assertFalse(verifier.verify(valid.copy(signature = "malformed!"), INSTALLATION_ID, "user-a"))
    val changedBytes =
        Base64Url.decode(valid.signature).also { it[0] = (it[0].toInt() xor 1).toByte() }
    assertFalse(
        verifier.verify(
            valid.copy(signature = Base64Url.encode(changedBytes)),
            INSTALLATION_ID,
            "user-a",
        )
    )
  }
}
