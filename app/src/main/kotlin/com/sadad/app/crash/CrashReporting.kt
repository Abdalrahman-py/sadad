package com.sadad.app.crash

import android.content.Context
import com.sadad.app.BuildConfig
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid

internal object CrashReporting {
  fun init(
      context: Context,
      dsn: String = BuildConfig.SENTRY_DSN,
      debuggable: Boolean = BuildConfig.DEBUG,
  ) {
    if (dsn.isBlank() || debuggable) return
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
