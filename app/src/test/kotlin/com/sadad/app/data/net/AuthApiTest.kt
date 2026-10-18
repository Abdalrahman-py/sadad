package com.sadad.app.data.net

import com.sadad.app.data.crypto.Base64Url
import com.sadad.ledger.LedgerJson
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class AuthApiTest {
  @Test
  fun transportFailuresRemainIoExceptions() = runTest {
    val url =
        MockWebServer().use { server ->
          server.start()
          server.url("/").toString()
        }
    val api =
        AuthApi(OkHttpClient.Builder().retryOnConnectionFailure(false).build(), url, "publishable")
    try {
      api.signIn("shop", "private-password")
      fail("expected IOException")
    } catch (error: IOException) {
      assertFalse(error.toString().contains("private-password"))
    }
  }

  @Test
  fun malformedSuccessfulSessionsDoNotExposeResponseTokens() = runTest {
    MockWebServer().use { server ->
      server.start()
      server.enqueue(
          MockResponse.Builder()
              .body(
                  """{"access_token":"secret-token-value","refresh_token":"secret-refresh","expires_at":1800000000,"user":{"id":"user-a"}}"""
              )
              .build()
      )
      val api = AuthApi(OkHttpClient(), server.url("/").toString(), "publishable")
      val error =
          try {
            api.signIn("shop", "private-password")
            error("expected invalid session")
          } catch (error: ApiException) {
            error
          }
      assertEquals("temporary_service_failure", error.code)
      assertFalse(error.toString().contains("secret-token-value"))
      assertFalse(error.toString().contains("secret-refresh"))
      assertNull(error.cause)
    }
  }

  @Test
  fun refreshChangePasswordAndSignOutUseTheirAuthRoutes() = runTest {
    MockWebServer().use { server ->
      server.start()
      val token = "e30." + Base64Url.encode("{}".toByteArray()) + ".signature"
      server.enqueue(
          MockResponse.Builder()
              .body(
                  """{"access_token":"$token","refresh_token":"rotated","expires_at":1800000000,"user":{"id":"user-a"}}"""
              )
              .build()
      )
      server.enqueue(
          MockResponse.Builder().body("""{"ok":true,"forcePasswordChange":false}""").build()
      )
      server.enqueue(MockResponse.Builder().code(204).build())
      val api = AuthApi(OkHttpClient(), server.url("/").toString(), "publishable")
      val session = api.refresh("old-refresh")
      assertEquals("rotated", session.refreshToken)
      assertFalse(session.forcePasswordChange)
      assertFalse(session.toString().contains(token))
      api.changePassword(session.accessToken, "old-password", "new-password-long")
      api.signOut(session.accessToken)
      val refresh = server.takeRequest()
      assertEquals("/auth/v1/token?grant_type=refresh_token", refresh.target)
      assertEquals("""{"refresh_token":"old-refresh"}""", refresh.body!!.utf8())
      val change = server.takeRequest()
      assertEquals("/functions/v1/api/v1/me/password", change.target)
      assertEquals("Bearer $token", change.headers["Authorization"])
      assertEquals(
          """{"currentPassword":"old-password","newPassword":"new-password-long"}""",
          change.body!!.utf8(),
      )
      val logout = server.takeRequest()
      assertEquals("/auth/v1/logout", logout.target)
      assertEquals("POST", logout.method)
      for (request in listOf(refresh, change, logout)) assertEquals(
          "publishable",
          request.headers["apikey"],
      )
    }
  }

  @Test
  fun refusalsDoNotExposePasswordsOrRawServerBodies() = runTest {
    MockWebServer().use { server ->
      server.start()
      val api = AuthApi(OkHttpClient(), server.url("/").toString(), "publishable")
      for ((body, expected) in
          listOf(
              """{"code":"invalid_credentials","message":"private-password"}""" to
                  "invalid_credentials",
              "not json: private-password" to "temporary_service_failure",
              """{"code":null,"message":"private-password"}""" to "temporary_service_failure",
              """{"code":false,"message":"private-password"}""" to "temporary_service_failure",
          )) {
        server.enqueue(MockResponse.Builder().code(400).body(body).build())
        val error =
            try {
              api.signIn("shop", "private-password")
              error("expected refusal")
            } catch (error: ApiException) {
              error
            }
        assertEquals(400, error.status)
        assertEquals(expected, error.code)
        assertFalse(error.toString().contains("private-password"))
        assertNull(error.cause)
      }
    }
  }

  @Test
  fun signInUsesStoreEmailAndReadsPasswordChangeClaim() = runTest {
    MockWebServer().use { server ->
      server.start()
      val token =
          "e30." +
              Base64Url.encode(
                  """{"app_metadata":{"force_password_change":true}}""".toByteArray()
              ) +
              ".signature"
      server.enqueue(
          MockResponse.Builder()
              .body(
                  """{"access_token":"$token","refresh_token":"refresh","expires_at":1800000000,"user":{"id":"user-a"}}"""
              )
              .build()
      )
      val session =
          AuthApi(OkHttpClient(), server.url("/").toString(), "publishable")
              .signIn("shop", "private-password")
      assertEquals("user-a", session.userId)
      assertEquals(Instant.ofEpochSecond(1800000000), session.expiresAt)
      assertTrue(session.forcePasswordChange)
      val request = server.takeRequest()
      assertEquals("POST", request.method)
      assertEquals(
          "/auth/v1/token?grant_type=password",
          request.url.encodedPath + "?" + request.url.encodedQuery,
      )
      assertEquals("publishable", request.headers["apikey"])
      val body = LedgerJson.parseToJsonElement(request.body!!.utf8()).jsonObject
      assertEquals("shop@stores.sadad.invalid", body.getValue("email").jsonPrimitive.content)
      assertEquals("private-password", body.getValue("password").jsonPrimitive.content)
    }
  }
}
