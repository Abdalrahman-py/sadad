package com.sadad.app.data.net

import com.sadad.app.data.crypto.Base64Url
import com.sadad.app.data.crypto.InstallationSigner
import com.sadad.ledger.ProofText
import java.util.UUID
import okhttp3.Request
import okio.Buffer

/**
 * Intercepts requests on IO immediately before the shared client's call, without cloning the
 * client.
 */
internal object SigningInterceptor {
  fun intercept(request: Request): Request {
    val signer = request.tag(InstallationSigner::class.java) ?: return request
    val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
    val path =
        request.url.encodedPath.replace(Regex("^(?:/functions/v1)?/api(?=/|$)"), "").ifEmpty { "/" }
    val route = path + (request.url.encodedQuery?.let { "?$it" } ?: "")
    val time = System.currentTimeMillis()
    val nonce = UUID.randomUUID().toString()
    val text = ProofText.of(request.method, route, body, time, nonce, signer.installationId)
    return request
        .newBuilder()
        .header("x-sadad-installation", signer.installationId)
        .header("x-sadad-time", time.toString())
        .header("x-sadad-nonce", nonce)
        .header(
            "x-sadad-signature",
            Base64Url.encode(signer.sign(text.toByteArray(Charsets.UTF_8))),
        )
        .build()
  }
}
