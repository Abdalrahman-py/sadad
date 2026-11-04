package com.sadad.app.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal fun internetAvailable(context: Context): Boolean {
  val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
  val network = manager.activeNetwork ?: return false
  val capabilities = manager.getNetworkCapabilities(network) ?: return false
  return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
      capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/** Register only while the activity is started; a validated connection wakes sync immediately. */
internal fun connectivity(context: Context) = callbackFlow {
  val manager = context.getSystemService(ConnectivityManager::class.java)
  val callback =
      object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
          trySend(internetAvailable(context))
        }

        override fun onLost(network: Network) {
          trySend(internetAvailable(context))
        }
      }
  manager.registerDefaultNetworkCallback(callback)
  trySend(internetAvailable(context))
  awaitClose { manager.unregisterNetworkCallback(callback) }
}
    .distinctUntilChanged()
