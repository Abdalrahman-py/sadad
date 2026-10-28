package com.sadad.app.feature.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * Proves the downloaded file is the APK the release published and that Android will accept it.
 *
 * Neither Seal nor Mihon checks either of these, so a truncated download or a swapped asset only
 * surfaces as "application not installed" at the very end. Checking first gives the user a sentence
 * instead of a failure, and keeps a wrong-key APK off the installer.
 */
internal object ApkVerifier {
  private const val BUFFER_BYTES = 64 * 1024

  fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(BUFFER_BYTES)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().toHex()
  }

  fun digestOf(signature: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(signature).toHex()

  /**
   * A digest the downloader computed while streaming, in the same lowercase hex as the manifest.
   */
  fun hexOf(bytes: ByteArray): String = bytes.toHex()

  /**
   * The update must carry exactly the signing certificates this copy of the app was installed with.
   * An empty set on either side is a refusal: `getPackageArchiveInfo` returns nothing for a file it
   * cannot parse, and an unreadable APK is not a verified APK.
   */
  fun signedBySameKey(installed: Set<String>, update: Set<String>): Boolean =
      installed.isNotEmpty() && update.isNotEmpty() && installed == update

  fun installedSignatures(context: Context): Set<String> =
      signaturesOf(
          if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(signatureFlags().toLong()),
            )
          } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, signatureFlags())
          }
      )

  fun apkSignatures(context: Context, apk: File): Set<String> =
      signaturesOf(
          if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(signatureFlags().toLong()),
            )
          } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, signatureFlags())
          }
      )

  /** The `versionCode` the downloaded APK declares; null when the file is not a readable APK. */
  fun apkVersionCode(context: Context, apk: File): Int? =
      try {
        val info =
            if (Build.VERSION.SDK_INT >= 33) {
              context.packageManager.getPackageArchiveInfo(
                  apk.absolutePath,
                  PackageManager.PackageInfoFlags.of(0),
              )
            } else {
              @Suppress("DEPRECATION")
              context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            }
        info?.let {
          if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else legacyCode(it)
        }
      } catch (_: RuntimeException) {
        null
      }

  private fun signaturesOf(info: PackageInfo?): Set<String> {
    if (info == null) return emptySet()
    val signatures =
        if (Build.VERSION.SDK_INT >= 28) {
          info.signingInfo?.apkContentsSigners
        } else {
          @Suppress("DEPRECATION") info.signatures
        }
    return signatures.orEmpty().map { digestOf(it.toByteArray()) }.toSet()
  }

  private fun signatureFlags(): Int =
      if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
      else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

  @Suppress("DEPRECATION") private fun legacyCode(info: PackageInfo): Int = info.versionCode

  private fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (byte in this) {
      val value = byte.toInt() and 0xff
      out.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
    }
    return out.toString()
  }

  private const val HEX = "0123456789abcdef"
}
