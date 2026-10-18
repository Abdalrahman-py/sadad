package com.sadad.app.data.net

import com.sadad.app.data.crypto.Base64Url
import com.sadad.ledger.LedgerJson
import java.time.Instant
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Tokens are used only on the wire; even incidental string conversion redacts them. */
data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant,
    val userId: String,
    val forcePasswordChange: Boolean,
) {
  override fun toString(): String = "AuthSession(redacted)"
}

class AuthApi(client: OkHttpClient, supabaseUrl: String, publishableKey: String) {
  private val base = supabaseUrl.trimEnd('/')
  private val transport = ApiTransport(client, publishableKey)

  suspend fun signIn(username: String, password: String): AuthSession {
    val body = buildJsonObject {
      put("email", "$username@stores.sadad.invalid")
      put("password", password)
    }
    val response =
        transport.json(
            Request.Builder()
                .url("$base/auth/v1/token?grant_type=password")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )
    return sessionOf(response)
  }

  suspend fun refresh(refreshToken: String): AuthSession {
    val body = buildJsonObject { put("refresh_token", refreshToken) }
    return sessionOf(
        transport.json(
            Request.Builder()
                .url("$base/auth/v1/token?grant_type=refresh_token")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )
    )
  }

  suspend fun signOut(accessToken: String) {
    transport.json(
        Request.Builder()
            .url("$base/auth/v1/logout")
            .header("Authorization", "Bearer $accessToken")
            .post("".toRequestBody(JSON_MEDIA_TYPE))
            .build()
    )
  }

  suspend fun changePassword(accessToken: String, current: String, new: String) {
    val body = buildJsonObject {
      put("currentPassword", current)
      put("newPassword", new)
    }
    transport.json(
        Request.Builder()
            .url("$base/functions/v1/api/v1/me/password")
            .header("Authorization", "Bearer $accessToken")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    )
  }

  private fun sessionOf(response: JsonObject): AuthSession =
      try {
        val token = response.getValue("access_token").jsonPrimitive.content
        // Decode only: the server authenticates JWTs. user_metadata is never an authority.
        val claims =
            LedgerJson.parseToJsonElement(Base64Url.decode(token.split('.')[1]).decodeToString())
                .jsonObject
        AuthSession(
            token,
            response.getValue("refresh_token").jsonPrimitive.content,
            Instant.ofEpochSecond(response.getValue("expires_at").jsonPrimitive.long),
            response.getValue("user").jsonObject.getValue("id").jsonPrimitive.content,
            claims["app_metadata"]
                ?.jsonObject
                ?.get("force_password_change")
                ?.jsonPrimitive
                ?.booleanOrNull == true,
        )
      } catch (_: RuntimeException) {
        throw ApiException(200, "temporary_service_failure")
      }
}
