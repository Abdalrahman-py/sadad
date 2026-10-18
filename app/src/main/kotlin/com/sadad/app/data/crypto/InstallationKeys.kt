package com.sadad.app.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * The per-store P-256 key in the Android Keystore: not exportable, no user authentication (sync
 * runs in the background). The caller owns the alias (`installation-<storeUserId>`) and the
 * installation id.
 */
object InstallationKeys {
  private const val PROVIDER = "AndroidKeyStore"

  /** Generates a key under `alias` (replacing any key already there) and returns its signer. */
  fun create(alias: String, installationId: String): InstallationSigner {
    val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
    generator.initialize(
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
    )
    val keyPair = generator.generateKeyPair()
    return KeystoreSigner(installationId, keyPair.private, keyPair.public as ECPublicKey)
  }

  /** The signer for `alias`, or null when this install has no key yet. */
  fun load(alias: String, installationId: String): InstallationSigner? {
    val keyStore = keyStore()
    if (!keyStore.containsAlias(alias)) return null
    val privateKey = keyStore.getKey(alias, null) as PrivateKey
    val publicKey = keyStore.getCertificate(alias).publicKey as ECPublicKey
    return KeystoreSigner(installationId, privateKey, publicKey)
  }

  /** Removes the key ("remove store from this phone"); a missing alias is a no-op. */
  fun delete(alias: String) {
    keyStore().deleteEntry(alias)
  }

  private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
}
