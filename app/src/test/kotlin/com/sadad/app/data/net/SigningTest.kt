package com.sadad.app.data.net

import com.sadad.app.data.crypto.Base64Url
import com.sadad.app.data.crypto.Der
import com.sadad.app.data.crypto.SoftwareSigner
import com.sadad.app.data.crypto.toPublicKey
import com.sadad.ledger.LedgerJson
import com.sadad.ledger.OperationsRequest
import com.sadad.ledger.ProofText
import com.sadad.ledger.ReceiptStatus
import java.security.Signature
import java.util.UUID
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class SigningTest {
  @Test
  fun operationsSignsTheExactArabicBodyAndReadsEveryReceipt() = runTest {
    MockWebServer().use { server ->
      server.start()
      server.enqueue(MockResponse.Builder().body(envelope("receipts", receipts())).build())
      val signer = SoftwareSigner(INSTALLATION_ID)
      val batch =
          LedgerJson.decodeFromJsonElement(
              OperationsRequest.serializer(),
              fixture("operations").getValue("request"),
          )
      val result =
          LedgerApi(OkHttpClient(), server.url("/functions/v1/api").toString(), "publishable")
              .operations(SESSION, signer, batch.operations, recoveryPermit = "permit-id")
      assertEquals(3, result.size)
      assertEquals(ReceiptStatus.ACCEPTED, result[0].status)
      assertEquals(3L, result[2].sequence)
      assertEquals(EPOCH, result[2].epoch)
      val request = server.takeRequest()
      assertEquals("POST", request.method)
      assertEquals("permit-id", request.headers["x-sadad-recovery"])
      assertEquals(
          batch,
          LedgerJson.decodeFromString(OperationsRequest.serializer(), request.body!!.utf8()),
      )
      assertTrue(request.body!!.utf8().contains("أبو خالد"))
      verifyProof(request, signer, "/v1/mobile/operations")
    }
  }

  @Test
  fun changesSignsTheQueryAndAnEmptyBody() = runTest {
    MockWebServer().use { server ->
      server.start()
      server.enqueue(MockResponse.Builder().body(envelope("changes", changes())).build())
      val signer = SoftwareSigner(INSTALLATION_ID)
      val page =
          LedgerApi(OkHttpClient(), server.url("/functions/v1/api").toString(), "publishable")
              .changes(SESSION, signer, after = 1, limit = 200)
      assertEquals(3L, page.nextCursor)
      assertEquals(2, page.events.size)
      val request = server.takeRequest()
      assertEquals("/functions/v1/api/v1/mobile/changes?after=1&limit=200", request.target)
      assertEquals(0L, request.bodySize)
      verifyProof(request, signer, "/v1/mobile/changes?after=1&limit=200")
    }
  }

  private fun verifyProof(request: RecordedRequest, signer: SoftwareSigner, route: String) {
    assertEquals("publishable", request.headers["apikey"])
    assertEquals("Bearer access-token", request.headers["Authorization"])
    assertEquals(INSTALLATION_ID, request.headers["x-sadad-installation"])
    val time = request.headers["x-sadad-time"]!!
    assertTrue(time.matches(Regex("[0-9]{13}")))
    UUID.fromString(request.headers["x-sadad-nonce"]!!)
    val proof =
        ProofText.of(
            request.method,
            route,
            request.body?.utf8().orEmpty(),
            time.toLong(),
            request.headers["x-sadad-nonce"]!!,
            INSTALLATION_ID,
        )
    val verifier = Signature.getInstance("SHA256withECDSA")
    verifier.initVerify(signer.publicKey.toPublicKey())
    verifier.update(proof.toByteArray())
    assertTrue(verifier.verify(Der.toDer(Base64Url.decode(request.headers["x-sadad-signature"]!!))))
  }
}
