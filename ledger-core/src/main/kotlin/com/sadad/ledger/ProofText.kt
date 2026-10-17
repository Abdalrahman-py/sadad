package com.sadad.ledger

import java.security.MessageDigest

/**
 * The text an installation signs for each request (`proof.ts` in the api). Signing happens in
 * `:app` with the Keystore key; this only builds the text.
 */
object ProofText {
  /**
   * Seven lines joined by `\n`, no trailing newline: `sadid-v3`, the method, the route (the path
   * from `/v1` plus the query string), the SHA-256 hex of the UTF-8 body, the 13-digit epoch
   * milliseconds, the nonce and the installation id.
   */
  fun of(
      method: String,
      route: String,
      body: String,
      time: Long,
      nonce: String,
      installationId: String,
  ): String =
      listOf(
              "sadid-v3",
              method.uppercase(),
              route,
              sha256Hex(body),
              time.toString(),
              nonce,
              installationId,
          )
          .joinToString("\n")

  /** Lowercase hex SHA-256 of the UTF-8 bytes. */
  fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
  }
}
