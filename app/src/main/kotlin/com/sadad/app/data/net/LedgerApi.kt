package com.sadad.app.data.net

import com.sadad.app.data.crypto.Base64Url
import com.sadad.app.data.crypto.InstallationSigner
import com.sadad.app.data.crypto.PublicJwk
import com.sadad.ledger.Changes
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerJson
import com.sadad.ledger.OperationsRequest
import com.sadad.ledger.Receipt
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class LedgerApi(client: OkHttpClient, apiBaseUrl: String, publishableKey: String) {
  private val base = apiBaseUrl.trimEnd('/')
  private val transport = ApiTransport(client, publishableKey)

  suspend fun lease(session: AuthSession, signer: InstallationSigner): Lease {
    val response =
        transport.json(
            request(session, signer)
                .url("$base/v1/mobile/lease")
                .post("".toRequestBody(null))
                .build()
        )
    return decode(response, "lease") {
      val wire = it.jsonObject
      val raw = wire.getValue("payload").jsonPrimitive.content
      Lease(
          LedgerJson.decodeFromString(
              LeasePayload.serializer(),
              Base64Url.decode(raw).decodeToString(),
          ),
          raw,
          wire.getValue("signature").jsonPrimitive.content,
      )
    }
  }

  suspend fun enroll(
      session: AuthSession,
      signer: InstallationSigner,
      publicKey: PublicJwk,
      label: String,
      staffName: String,
  ): Installation {
    val body = buildJsonObject {
      put(
          "publicKey",
          buildJsonObject {
            put("kty", "EC")
            put("crv", "P-256")
            put("x", publicKey.x)
            put("y", publicKey.y)
          },
      )
      put("label", label)
      put("staffName", staffName)
    }
    val response =
        transport.json(
            request(session, signer)
                .url("$base/v1/mobile/enroll")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )
    return decode(response, "installation") {
      LedgerJson.decodeFromJsonElement(Installation.serializer(), it)
    }
  }

  suspend fun operations(
      session: AuthSession,
      signer: InstallationSigner,
      commands: List<LedgerCommand>,
      recoveryPermit: String? = null,
  ): List<Receipt> {
    val body =
        LedgerJson.encodeToString(OperationsRequest.serializer(), OperationsRequest(commands))
    val builder =
        request(session, signer)
            .url("$base/v1/mobile/operations")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
    if (recoveryPermit != null) builder.header("x-sadad-recovery", recoveryPermit)
    return decode(transport.json(builder.build()), "receipts") {
      LedgerJson.decodeFromJsonElement(ListSerializer(Receipt.serializer()), it)
    }
  }

  suspend fun changes(
      session: AuthSession,
      signer: InstallationSigner,
      after: Long,
      limit: Int = 500,
  ): Changes {
    val url =
        "$base/v1/mobile/changes"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("after", after.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
    val response = transport.json(request(session, signer).url(url).get().build())
    return decode(response, "changes") {
      LedgerJson.decodeFromJsonElement(Changes.serializer(), it)
    }
  }

  private fun request(session: AuthSession, signer: InstallationSigner): Request.Builder =
      Request.Builder()
          .header("Authorization", "Bearer ${session.accessToken}")
          .tag(InstallationSigner::class.java, signer)

  private fun <T> decode(
      response: JsonObject,
      name: String,
      decode: (kotlinx.serialization.json.JsonElement) -> T,
  ): T =
      try {
        decode(response.getValue(name))
      } catch (_: RuntimeException) {
        throw ApiException(200, "temporary_service_failure")
      }
}
