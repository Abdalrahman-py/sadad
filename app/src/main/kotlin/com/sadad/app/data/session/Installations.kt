package com.sadad.app.data.session

import com.sadad.app.data.crypto.InstallationKeys
import com.sadad.app.data.crypto.InstallationSigner

/**
 * The one installation key per store, injected so tests use a software key instead of the Keystore.
 */
interface Installations {
  fun create(keyAlias: String, installationId: String): InstallationSigner

  fun load(keyAlias: String, installationId: String): InstallationSigner?

  fun delete(keyAlias: String)
}

/** The Android Keystore implementation; one P-256 key per store alias. */
object KeystoreInstallations : Installations {
  override fun create(keyAlias: String, installationId: String): InstallationSigner =
      InstallationKeys.create(keyAlias, installationId)

  override fun load(keyAlias: String, installationId: String): InstallationSigner? =
      InstallationKeys.load(keyAlias, installationId)

  override fun delete(keyAlias: String) = InstallationKeys.delete(keyAlias)
}
