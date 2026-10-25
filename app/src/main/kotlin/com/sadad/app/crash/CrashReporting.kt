package com.sadad.app.crash

import android.content.Context
import com.sadad.app.BuildConfig
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid

/**
 * Starts crash reporting only when a DSN was handed to the build (`-PSADAD_SENTRY_DSN=…`).
 *
 * The DSN is the whole gate, so every variant stays silent by default: `debug`, `release` and
 * `benchmark` all ship an empty DSN unless a build passes one, and nothing is sent to Sentry
 * without that explicit flag. That is also what lets ticket 77 use the debug probe: a debug build
 * given a DSN on purpose reports, one built normally does not. (A gate on `debuggable` would make
 * the probe unable to ever report, since the probe lives in the `debug` source set.)
 */
internal object CrashReporting {
  fun init(context: Context, dsn: String = BuildConfig.SENTRY_DSN) {
    if (dsn.isBlank()) return
    SentryAndroid.init(context) { options ->
      options.dsn = dsn
      options.isSendDefaultPii = false
      options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
        SentryScrubber.beforeSend(event)
      }
      options.beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { crumb, _ ->
        SentryScrubber.beforeBreadcrumb(crumb)
      }
    }
  }
}
