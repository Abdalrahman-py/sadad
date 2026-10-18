package com.sadad.app.data.crypto

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/** A throwaway P-256 key for the tests that need one instead of the Keystore. */
fun ecP256KeyPair(): KeyPair =
    KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()
