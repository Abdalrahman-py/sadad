package com.sadad.app.data.crypto

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

/** The `enroll` public key: `{ kty: "EC", crv: "P-256", x, y }`, each coordinate base64url. */
data class PublicJwk(val x: String, val y: String) {
  companion object {
    /** Affine x and y as unsigned 32-byte big-endian, base64url, unpadded. */
    fun of(key: ECPublicKey): PublicJwk =
        PublicJwk(
            x = Base64Url.encode(fixed32(key.w.affineX.toByteArray())),
            y = Base64Url.encode(fixed32(key.w.affineY.toByteArray())),
        )
  }
}

/** The P-256 parameters, the curve both the Keystore key and `proof.ts` use. */
internal val P256: ECParameterSpec =
    AlgorithmParameters.getInstance("EC").run {
      init(ECGenParameterSpec("secp256r1"))
      getParameterSpec(ECParameterSpec::class.java)
    }

/** Back to the key the JCA `Signature` verifies with; the tests use it, the app only signs. */
internal fun PublicJwk.toPublicKey(): ECPublicKey =
    KeyFactory.getInstance("EC")
        .generatePublic(
            ECPublicKeySpec(
                ECPoint(BigInteger(1, Base64Url.decode(x)), BigInteger(1, Base64Url.decode(y))),
                P256,
            )
        ) as ECPublicKey
