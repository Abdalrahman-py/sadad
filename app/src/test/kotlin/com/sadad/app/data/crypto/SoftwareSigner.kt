package com.sadad.app.data.crypto

import java.security.KeyPair
import java.security.Signature
import java.security.interfaces.ECPublicKey

/**
 * The `InstallationSigner` the JVM tests use in place of the Keystore: the same P-256 signature, a
 * `KeyPairGenerator("EC")` key instead of a non-exportable one.
 */
class SoftwareSigner(
    override val installationId: String,
    private val keyPair: KeyPair = ecP256KeyPair(),
) : InstallationSigner {
  override val publicKey: PublicJwk = PublicJwk.of(keyPair.public as ECPublicKey)

  override fun sign(data: ByteArray): ByteArray {
    val signature = Signature.getInstance("SHA256withECDSA")
    signature.initSign(keyPair.private)
    signature.update(data)
    return Der.toRaw(signature.sign())
  }
}
