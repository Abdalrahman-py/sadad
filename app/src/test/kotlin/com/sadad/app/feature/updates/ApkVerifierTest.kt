package com.sadad.app.feature.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The two proofs a downloaded APK has to pass before the installer sees it. */
class ApkVerifierTest {
  @get:Rule val folder = TemporaryFolder()

  @Test
  fun theDigestOfAFileIsItsSha256() {
    val file = folder.newFile("apk.bin")
    file.writeText("abc")
    // The published digest of the three bytes "abc".
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        ApkVerifier.sha256(file),
    )
  }

  @Test
  fun aChangedByteChangesTheDigest() {
    val file = folder.newFile("apk.bin")
    file.writeText("abc")
    val before = ApkVerifier.sha256(file)
    file.appendText(" ")
    assertFalse(before == ApkVerifier.sha256(file))
  }

  @Test
  fun theSameCertificateIsAcceptedAndAnythingElseIsNot() {
    val installed = setOf("aa11")
    assertTrue(ApkVerifier.signedBySameKey(installed, setOf("aa11")))
    assertFalse(ApkVerifier.signedBySameKey(installed, setOf("bb22")))
    assertFalse(ApkVerifier.signedBySameKey(installed, setOf("aa11", "bb22")))
  }

  @Test
  fun anApkWithNoReadableCertificateIsNeverTrusted() {
    // An empty set means the file could not be parsed; an unreadable APK is not a verified one.
    assertFalse(ApkVerifier.signedBySameKey(emptySet(), emptySet()))
    assertFalse(ApkVerifier.signedBySameKey(setOf("aa11"), emptySet()))
    assertFalse(ApkVerifier.signedBySameKey(emptySet(), setOf("aa11")))
  }

  @Test
  fun aSignatureDigestIsLowercaseHex() {
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        ApkVerifier.digestOf("abc".toByteArray()),
    )
  }
}
