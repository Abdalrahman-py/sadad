package com.sadad.app.feature.recovery

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.AppGraph
import com.sadad.app.R
import com.sadad.app.data.recovery.Recovery
import com.sadad.app.data.session.StoreScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The open store's installation status and the message the last recovery action produced. */
data class RecoveryState(val status: String? = null, val message: String? = null)

/** Recovery actions are one-shot, so only their outcome and the installation status are state. */
class RecoveryViewModel(private val graph: AppGraph, context: Context) : ViewModel() {
  private val appContext = context.applicationContext
  private val mutable = MutableStateFlow(RecoveryState())
  val state: StateFlow<RecoveryState> = mutable.asStateFlow()

  init {
    viewModelScope.launch {
      graph.accounts.state.collect { file ->
        val storeUserId = graph.current.value?.account?.storeUserId ?: file.activeStoreUserId
        val status =
            file.accounts.firstOrNull { it.storeUserId == storeUserId }?.installation?.status
        mutable.update { it.copy(status = status) }
      }
    }
  }

  suspend fun export(passphrase: CharArray): ByteArray? {
    try {
      return guarded { Recovery.export(scope(), passphrase) }
    } finally {
      passphrase.fill('\u0000')
    }
  }

  suspend fun import(file: ByteArray, passphrase: CharArray): Int? {
    try {
      return guarded { Recovery.import(scope(), file, passphrase) }
    } finally {
      passphrase.fill('\u0000')
    }
  }

  suspend fun origins(): List<String> = guarded { Recovery.origins(scope()) } ?: emptyList()

  suspend fun manifest(origin: String): ByteArray? = guarded { Recovery.manifest(scope(), origin) }

  fun enterPermit(origin: String, permit: String) {
    viewModelScope.launch {
      try {
        Recovery.setPermit(scope(), origin, permit)
        showMessage(appContext.getString(R.string.recovery_permit_stored))
        runCatching { graph.syncStore(scope().account.storeUserId) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: IllegalArgumentException) {
        showMessage(error.message ?: appContext.getString(R.string.recovery_failed))
      } catch (_: Exception) {
        showMessage(appContext.getString(R.string.recovery_failed))
      }
    }
  }

  fun registerAgain() {
    viewModelScope.launch {
      try {
        val registered = graph.sessions.registerAgain(scope())
        showMessage(
            appContext.getString(
                if (registered) R.string.recovery_registered else R.string.recovery_register_failed
            )
        )
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        showMessage(appContext.getString(R.string.recovery_register_failed))
      }
    }
  }

  fun showMessage(text: String?) = mutable.update { it.copy(message = text) }

  private fun scope(): StoreScope = checkNotNull(graph.current.value) { "no open store" }

  private suspend fun <T> guarded(block: suspend () -> T): T? =
      try {
        block()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: IllegalArgumentException) {
        showMessage(error.message)
        null
      } catch (_: Exception) {
        showMessage(appContext.getString(R.string.recovery_failed))
        null
      }
}
