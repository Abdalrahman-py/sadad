package com.sadad.app.data.net

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.data.crypto.SoftwareSigner
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RefusalsTest {
  @Test
  fun everyMobileRefusalKeepsItsStatusAndHasAnArabicMessage() = runTest {
    val messages = ErrorMessages(ApplicationProvider.getApplicationContext<Context>())
    MockWebServer().use { server ->
      server.start()
      val api = LedgerApi(OkHttpClient(), server.url("/functions/v1/api").toString(), "publishable")
      val signer = SoftwareSigner(INSTALLATION_ID)
      for ((_, element) in fixture("refusals")) {
        if (element !is JsonObject) continue
        val status = element.getValue("status").jsonPrimitive.int
        val code = element.getValue("code").jsonPrimitive.content
        server.enqueue(
            MockResponse.Builder()
                .code(status)
                .body(
                    buildJsonObject {
                      put("ok", false)
                      put("code", code)
                    }
                        .toString()
                )
                .build()
        )
        val error =
            try {
              api.changes(SESSION, signer, 0)
              error("expected refusal")
            } catch (error: ApiException) {
              error
            }
        assertEquals(status, error.status)
        assertEquals(code, error.code)
        assertTrue(code, messages.text(code).any { it.code in 0x0600..0x06ff })
      }
      assertEquals(messages.text("temporary_service_failure"), messages.text("unknown-code"))
    }
  }
}
