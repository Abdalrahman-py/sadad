package com.sadad.app.data.net

import com.sadad.ledger.LedgerJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request

internal val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/** No server body (which may contain credentials) is included in the exception message. */
class ApiException(val status: Int, val code: String) :
    Exception("API request failed (HTTP $status)")

internal class ApiTransport(private val client: OkHttpClient, private val publishableKey: String) {
  suspend fun json(request: Request): JsonObject =
      withContext(Dispatchers.IO) {
        val outgoing =
            SigningInterceptor.intercept(
                request.newBuilder().header("apikey", publishableKey).build()
            )
        client.newCall(outgoing).execute().use { response ->
          val raw = response.body.string()
          val json =
              try {
                if (raw.isBlank()) buildJsonObject {}
                else LedgerJson.parseToJsonElement(raw).jsonObject
              } catch (_: RuntimeException) {
                throw ApiException(response.code, "temporary_service_failure")
              }
          if (!response.isSuccessful) {
            val code =
                (json["code"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() } ?: "temporary_service_failure"
            throw ApiException(response.code, code)
          }
          json
        }
      }
}
