package com.sadad.app.data.net

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** Pass this same client to both APIs; it owns their shared connection pool. */
object ApiHttpClient {
  val client: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
  }
}
