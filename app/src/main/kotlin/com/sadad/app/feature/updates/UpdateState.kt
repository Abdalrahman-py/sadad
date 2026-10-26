package com.sadad.app.feature.updates

/** What the settings row, the launch prompt and the update screen all read. */
internal data class UpdateState(
    val phase: Phase = Phase.IDLE,
    val manifest: UpdateManifest? = null,
    /** 0..100 while [Phase.DOWNLOADING]. */
    val progress: Int = 0,
    val error: UpdateError? = null,
    val lastCheckMs: Long? = null,
    /** The launch prompt only; false after a snooze while the settings row keeps the update. */
    val prompts: Boolean = false,
) {
  enum class Phase {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    DOWNLOADING,
    READY,
    INSTALLING,
    FAILED,
  }

  /** True when there is a newer release, whatever phase the download is in. */
  val hasUpdate: Boolean
    get() =
        manifest != null &&
            phase in
                setOf(
                    Phase.AVAILABLE,
                    Phase.DOWNLOADING,
                    Phase.READY,
                    Phase.INSTALLING,
                )
}

internal enum class UpdateError {
  /** The release server was not reached, or answered with something other than 200. */
  NETWORK,

  /** The release exists but carries no `update.json`. */
  NO_MANIFEST,

  /** The manifest is malformed or belongs to the other channel. */
  BAD_MANIFEST,
  DOWNLOAD_FAILED,
  NO_SPACE,

  /** The bytes on disk are not the bytes the release published. */
  CHECKSUM_MISMATCH,

  /** The APK is signed with another key, so Android would refuse it. */
  SIGNATURE_MISMATCH,

  /** «تثبيت التطبيقات من هذا المصدر» is still off for Sadad. */
  NEEDS_PERMISSION,
  INSTALL_FAILED,
  INSTALL_CANCELLED,
}
