package com.sadad.app.data.crypto

/**
 * One per-store installation. `sign` returns the raw 64-byte `r‖s` the `api` verifies (see
 * `proof.ts`); `publicKey` is the JWK `enroll` takes. Every call blocks, so callers run it on
 * `Dispatchers.IO`.
 */
interface InstallationSigner {
  val installationId: String

  val publicKey: PublicJwk

  fun sign(data: ByteArray): ByteArray
}
