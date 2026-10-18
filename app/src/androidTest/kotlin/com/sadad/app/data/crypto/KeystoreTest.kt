package com.sadad.app.data.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import java.security.Signature
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real Keystore, which the JVM unit tests cannot reach (ticket 41). A human runs this over adb:
 * `./gradlew connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val alias = "installation-test-41"

  @After
  fun removeKeysAndFile() {
    InstallationKeys.delete(alias)
    deleteWrapKey()
    File(context.filesDir, DataKey.FILE_NAME).delete()
    DataKey.forget()
  }

  @Test
  fun createLoadSignAndDelete() {
    InstallationKeys.create(alias, INSTALLATION_ID)
    val signer = InstallationKeys.load(alias, INSTALLATION_ID)
    assertNotNull(signer)
    val message = "sadid-v3\nGET\n/v1/mobile/changes".toByteArray(Charsets.UTF_8)
    val signature = requireNotNull(signer).sign(message)
    assertEquals(64, signature.size)
    val verifier = Signature.getInstance("SHA256withECDSA")
    verifier.initVerify(requireNotNull(signer).publicKey.toPublicKey())
    verifier.update(message)
    assertTrue(verifier.verify(Der.toDer(signature)))

    InstallationKeys.delete(alias)
    assertNull(InstallationKeys.load(alias, INSTALLATION_ID))
  }

  @Test
  fun theDataKeyIsCreatedOnceAndReadBackFromTheFile() {
    val first = DataKey.get(context)
    assertSame("a second get returns the cached key", first, DataKey.get(context))
    assertTrue(File(context.filesDir, DataKey.FILE_NAME).isFile)
    // A fresh instance reads data-key.bin back to the same key.
    DataKey.forget()
    assertArrayEquals(first.key.encoded, DataKey.get(context).key.encoded)
  }

  @Test
  fun aFileWithoutItsWrapKeyIsLost() {
    DataKey.get(context)
    deleteWrapKey()
    DataKey.forget()
    assertThrows(DataKeyLost::class.java) { DataKey.get(context) }
  }

  private fun deleteWrapKey() {
    KeyStore.getInstance("AndroidKeyStore").run {
      load(null)
      deleteEntry(DataKey.WRAP_ALIAS)
    }
  }

  private companion object {
    const val INSTALLATION_ID = "8e4b1f72-6c3a-4d95-b0e8-2a7f5c19d463"
  }
}
