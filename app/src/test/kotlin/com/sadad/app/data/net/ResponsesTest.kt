package com.sadad.app.data.net

import com.sadad.app.data.crypto.Base64Url
import com.sadad.app.data.crypto.SoftwareSigner
import com.sadad.ledger.LedgerJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ResponsesTest {
  @Test
  fun leaseRetainsTheExactSignedPayloadAndUsesAnEmptyPostBody() = runTest {
    MockWebServer().use { server ->
      server.start()
      val payload =
          """{"schemaVersion":1,"projectRef":"example","storeId":7,"userId":"user-a","installationId":"$INSTALLATION_ID","generation":1,"issuedAt":1800000000000,"expiresAt":1800604800000,"allowed":["contact.create","debt.create","payment.create"]}"""
      val raw = Base64Url.encode(payload.toByteArray())
      server.enqueue(
          MockResponse.Builder()
              .body(
                  """{"ok":true,"lease":{"payload":"$raw","signature":"server-signature","publicKey":{"x":"untrusted","y":"untrusted"}}}"""
              )
              .build()
      )
      val lease =
          LedgerApi(OkHttpClient(), server.url("/functions/v1/api").toString(), "publishable")
              .lease(SESSION, SoftwareSigner(INSTALLATION_ID))
      assertEquals(raw, lease.raw)
      assertEquals("server-signature", lease.signature)
      assertEquals(7L, lease.payload.storeId)
      assertEquals(1800000000000L, lease.payload.issuedAt)
      assertEquals(1800604800000L, lease.payload.expiresAt)
      assertEquals(listOf("contact.create", "debt.create", "payment.create"), lease.payload.allowed)
      val request = server.takeRequest()
      assertEquals("POST", request.method)
      assertEquals("/functions/v1/api/v1/mobile/lease", request.target)
      assertEquals(0L, request.bodySize)
      assertNotNull(request.headers["x-sadad-signature"])
    }
  }

  @Test
  fun enrollUsesTheInstallationPublicKeyAndReadsItsPermissions() = runTest {
    MockWebServer().use { server ->
      server.start()
      server.enqueue(
          MockResponse.Builder().body(fixture("enroll").getValue("response").toString()).build()
      )
      val signer = SoftwareSigner(INSTALLATION_ID)
      val installation =
          LedgerApi(OkHttpClient(), server.url("/functions/v1/api").toString(), "publishable")
              .enroll(SESSION, signer, signer.publicKey, "Redmi M2101K7BI", "سامي")
      assertEquals(INSTALLATION_ID, installation.installationId)
      assertEquals(1, installation.generation)
      assertEquals("pending", installation.status)
      assertEquals("Redmi M2101K7BI", installation.label)
      assertEquals("سامي", installation.staffName)
      assertEquals(true, installation.permissions["registerPayments"])
      val request = server.takeRequest()
      assertEquals("/functions/v1/api/v1/mobile/enroll", request.target)
      val body = LedgerJson.parseToJsonElement(request.body!!.utf8()).jsonObject
      val expected = fixture("enroll").getValue("request").jsonObject
      assertEquals(expected.getValue("label"), body.getValue("label"))
      assertEquals(expected.getValue("staffName"), body.getValue("staffName"))
      val key = body.getValue("publicKey").jsonObject
      assertEquals("EC", key.getValue("kty").jsonPrimitive.content)
      assertEquals("P-256", key.getValue("crv").jsonPrimitive.content)
      assertEquals(signer.publicKey.x, key.getValue("x").jsonPrimitive.content)
      assertEquals(signer.publicKey.y, key.getValue("y").jsonPrimitive.content)
      assertNotNull(request.headers["x-sadad-signature"])
    }
  }
}
