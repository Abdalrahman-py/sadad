package com.sadad.app.crash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SentryProbe : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    throw RuntimeException("probe: Ali 120.50")
  }
}
