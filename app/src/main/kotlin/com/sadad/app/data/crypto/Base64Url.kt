package com.sadad.app.data.crypto

import java.util.Base64

/** URL-safe base64 without padding, the encoding the `api` contract uses on the wire. */
object Base64Url {
  fun encode(bytes: ByteArray): String =
      Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

  fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
}
