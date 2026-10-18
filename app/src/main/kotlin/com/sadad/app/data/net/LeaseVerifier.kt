package com.sadad.app.data.net

import com.sadad.app.BuildConfig
import com.sadad.app.data.crypto.Base64Url
import com.sadad.app.data.crypto.Der
import com.sadad.app.data.crypto.PublicJwk
import com.sadad.app.data.crypto.toPublicKey
import com.sadad.ledger.LedgerJson
import java.net.URI
import java.security.GeneralSecurityException
import java.security.Signature

/**
 * Verifies the server's original bytes with the build-pinned key, never a key from the response.
 */
class LeaseVerifier(private val publicX: String, private val publicY: String) {
  fun verify(lease: Lease, installationId: String, userId: String): Boolean {
    if (publicX.isBlank() || publicY.isBlank()) return false
    return try {
      val bytes = Base64Url.decode(lease.raw)
      val payload = LedgerJson.decodeFromString(LeasePayload.serializer(), bytes.decodeToString())
      if (
          payload != lease.payload ||
              payload.schemaVersion != 1 ||
              payload.projectRef != URI(BuildConfig.SUPABASE_URL).host.substringBefore('.') ||
              payload.installationId != installationId ||
              payload.userId != userId ||
              Math.subtractExact(payload.expiresAt, payload.issuedAt) != 604800000L
      )
          return false
      val verifier = Signature.getInstance("SHA256withECDSA")
      verifier.initVerify(PublicJwk(publicX, publicY).toPublicKey())
      verifier.update(bytes)
      verifier.verify(Der.toDer(Base64Url.decode(lease.signature)))
    } catch (_: RuntimeException) {
      false
    } catch (_: GeneralSecurityException) {
      false
    }
  }
}
