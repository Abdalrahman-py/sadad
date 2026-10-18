package com.sadad.app.data.crypto

import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey

/** `Signature` produces DER; the `api` verifies raw `r‖s`, so `sign` converts. */
internal class KeystoreSigner(
    override val installationId: String,
    private val privateKey: PrivateKey,
    publicKey: ECPublicKey,
) : InstallationSigner {
  override val publicKey: PublicJwk = PublicJwk.of(publicKey)

  override fun sign(data: ByteArray): ByteArray {
    val signature = Signature.getInstance("SHA256withECDSA")
    signature.initSign(privateKey)
    signature.update(data)
    return Der.toRaw(signature.sign())
  }
}
