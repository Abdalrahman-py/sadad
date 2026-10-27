package com.sadad.app.feature.auth

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.AppGraph
import com.sadad.app.R
import com.sadad.app.data.net.ApiException
import com.sadad.app.data.sync.SyncStatus
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginState(
    val username: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val destination: Any? = null,
)

class LoginViewModel(
    private val graph: AppGraph,
    private val saved: SavedStateHandle,
    private val context: Context,
) : ViewModel() {
  private val mutable =
      MutableStateFlow(LoginState(username = saved.get<String>(USERNAME).orEmpty()))
  val state: StateFlow<LoginState> = mutable.asStateFlow()

  fun onUsernameChange(value: String) {
    saved[USERNAME] = value
    mutable.update { it.copy(username = value, error = null) }
  }

  fun onPasswordChange(value: String) {
    mutable.update { it.copy(password = value, error = null) }
  }

  fun signIn() {
    if (mutable.value.busy) return
    val current = mutable.value
    viewModelScope.launch {
      mutable.update { it.copy(busy = true, error = null) }
      try {
        // A pasted or autocorrected space at either end is never part of the credential.
        graph.sessions.signIn(current.username.trim(), current.password.trim())
        mutable.update { it.copy(busy = false, destination = graph.startRoute()) }
      } catch (refused: ApiException) {
        val text =
            if (refused.code == "invalid_credentials") {
              context.getString(R.string.error_invalid_credentials)
            } else {
              graph.errorMessages.text(refused.code)
            }
        mutable.update { it.copy(busy = false, error = text) }
      } catch (_: IOException) {
        mutable.update {
          it.copy(busy = false, error = graph.errorMessages.text("temporary_service_failure"))
        }
      }
    }
  }

  private companion object {
    const val USERNAME = "username"
  }
}

data class PasswordChangeState(
    val current: String = "",
    val new: String = "",
    val confirm: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val destination: Any? = null,
)

class PasswordChangeViewModel(
    private val graph: AppGraph,
    private val context: Context,
) : ViewModel() {
  private val mutable = MutableStateFlow(PasswordChangeState())
  val state: StateFlow<PasswordChangeState> = mutable.asStateFlow()

  fun onCurrentChange(value: String) = mutable.update { it.copy(current = value, error = null) }

  fun onNewChange(value: String) = mutable.update { it.copy(new = value, error = null) }

  fun onConfirmChange(value: String) = mutable.update { it.copy(confirm = value, error = null) }

  fun change() {
    if (mutable.value.busy) return
    val current = mutable.value
    val invalid =
        when {
          current.new.length !in PASSWORD_MIN..PASSWORD_MAX ->
              context.getString(R.string.error_password_length)
          current.new != current.confirm -> context.getString(R.string.error_password_mismatch)
          current.new == current.current -> context.getString(R.string.error_password_same)
          else -> null
        }
    if (invalid != null) {
      mutable.update { it.copy(error = invalid) }
      return
    }
    viewModelScope.launch {
      mutable.update { it.copy(busy = true, error = null) }
      try {
        graph.sessions.changePassword(current.current, current.new)
        mutable.update { it.copy(busy = false, destination = graph.startRoute()) }
      } catch (refused: ApiException) {
        mutable.update { it.copy(busy = false, error = graph.errorMessages.text(refused.code)) }
      } catch (_: IOException) {
        mutable.update {
          it.copy(busy = false, error = graph.errorMessages.text("temporary_service_failure"))
        }
      }
    }
  }

  // Internal so the screen's live checklist reads the same limits the save checks.
  internal companion object {
    const val PASSWORD_MIN = 12
    const val PASSWORD_MAX = 128
  }
}

data class AwaitingApprovalState(
    val loading: Boolean = true,
    val needsDetails: Boolean = false,
    val staffName: String = "",
    val deviceLabel: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val status: SyncStatus? = null,
    val statusText: String? = null,
    val destination: Any? = null,
)

class AwaitingApprovalViewModel(
    private val graph: AppGraph,
    private val context: Context,
    private val statusFor: (String) -> StateFlow<SyncStatus> = graph::syncStatus,
    private val sync: suspend (String) -> Unit = { graph.syncStore(it) },
) : ViewModel() {
  private val mutable = MutableStateFlow(AwaitingApprovalState())
  val state: StateFlow<AwaitingApprovalState> = mutable.asStateFlow()
  private var storeUserId: String? = null

  init {
    viewModelScope.launch {
      val id =
          graph.current.value?.account?.storeUserId
              ?: graph.accounts.file().activeStoreUserId
              ?: run {
                mutable.update { it.copy(loading = false) }
                return@launch
              }
      storeUserId = id
      val account = graph.accounts.account(id)
      mutable.update {
        it.copy(
            loading = false,
            needsDetails = account == null || account.staffName.isBlank(),
            staffName = account?.staffName.orEmpty(),
            deviceLabel = account?.label.orEmpty(),
        )
      }
      if (account == null) return@launch
      statusFor(id).collect(::onStatus)
    }
  }

  fun onStaffNameChange(value: String) = mutable.update { it.copy(staffName = value, error = null) }

  fun onDeviceLabelChange(value: String) = mutable.update {
    it.copy(deviceLabel = value, error = null)
  }

  fun save() {
    if (mutable.value.busy) return
    val staff = mutable.value.staffName.trim()
    val label = mutable.value.deviceLabel.trim()
    val invalid =
        when {
          staff.isEmpty() -> context.getString(R.string.error_staff_name_required)
          staff.length > STAFF_NAME_MAX -> context.getString(R.string.error_staff_name_long)
          label.length > DEVICE_LABEL_MAX -> context.getString(R.string.error_device_label_long)
          else -> null
        }
    if (invalid != null) {
      mutable.update { it.copy(error = invalid) }
      return
    }
    val id = storeUserId ?: return
    viewModelScope.launch {
      mutable.update { it.copy(busy = true, error = null) }
      graph.accounts.update(id) { it.copy(staffName = staff, label = label) }
      val synced = runSync(id)
      mutable.update { it.copy(busy = false, needsDetails = !synced) }
    }
  }

  fun checkAgain() {
    if (mutable.value.busy) return
    val id = storeUserId ?: return
    viewModelScope.launch {
      mutable.update { it.copy(busy = true, error = null) }
      runSync(id)
      mutable.update { it.copy(busy = false) }
    }
  }

  private suspend fun runSync(id: String): Boolean =
      try {
        sync(id)
        true
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutable.update { it.copy(error = graph.errorMessages.text("temporary_service_failure")) }
        false
      }

  private suspend fun onStatus(status: SyncStatus) {
    mutable.update { it.copy(status = status, statusText = statusMessage(status)) }
    if (status != SyncStatus.Idle || mutable.value.destination != null) return
    val id = storeUserId ?: return
    if (graph.accounts.account(id)?.installation?.status == "active") {
      mutable.update { it.copy(destination = graph.startRoute()) }
    }
  }

  private fun statusMessage(status: SyncStatus): String? =
      when (status) {
        SyncStatus.Idle -> null
        SyncStatus.Running -> context.getString(R.string.approval_running)
        SyncStatus.AwaitingApproval -> context.getString(R.string.approval_awaiting)
        SyncStatus.Offline -> context.getString(R.string.approval_offline)
        SyncStatus.Retry -> context.getString(R.string.approval_retry)
        SyncStatus.Revoked -> graph.errorMessages.text("installation_or_session_revoked")
        SyncStatus.Suspended -> graph.errorMessages.text("store_suspended")
        SyncStatus.SignedOut -> graph.errorMessages.text("authorization_required")
        SyncStatus.PasswordChangeRequired -> graph.errorMessages.text("password_change_required")
        is SyncStatus.Failed -> graph.errorMessages.text(status.code)
      }

  private companion object {
    const val STAFF_NAME_MAX = 80
    const val DEVICE_LABEL_MAX = 120
  }
}
