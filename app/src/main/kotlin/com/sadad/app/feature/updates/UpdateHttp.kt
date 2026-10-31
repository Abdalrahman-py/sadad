package com.sadad.app.feature.updates

import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Keep cancellation attached until the response body, not just its headers, has been consumed. */
internal suspend fun <T> updateCall(
    client: OkHttpClient,
    request: Request,
    read: (Response) -> T,
): T =
    withContext(Dispatchers.IO) {
      coroutineScope {
        val call = client.newCall(request)
        val cancellation =
            launch(start = CoroutineStart.UNDISPATCHED) {
              try {
                awaitCancellation()
              } finally {
                call.cancel()
              }
            }
        try {
          call.execute().use { response ->
            currentCoroutineContext().ensureActive()
            read(response)
          }
        } catch (failure: IOException) {
          currentCoroutineContext().ensureActive()
          throw failure
        } finally {
          cancellation.cancel()
        }
      }
    }
