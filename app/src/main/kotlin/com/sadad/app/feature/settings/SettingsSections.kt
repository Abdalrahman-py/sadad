package com.sadad.app.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sadad.app.R
import com.sadad.app.feature.history.PeriodFilter
import java.time.LocalDate

internal fun LazyListScope.storeSection(state: SettingsState, model: SettingsViewModel) {
  item {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
    ) {
      Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Heading(
              stringResource(R.string.settings_store_heading),
              color = MaterialTheme.colorScheme.onPrimary,
              modifier = Modifier.weight(1f),
          )
          TextButton(onClick = model::editProfile) {
            Icon(
                painter = painterResource(R.drawable.ic_edit_thin),
                contentDescription = stringResource(R.string.settings_store_edit),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
          }
        }
        Text(
            text =
                stringResource(
                    R.string.settings_store_name,
                    state.storeName.ifBlank { stringResource(R.string.settings_default_store) },
                ),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
        Text(
            text =
                stringResource(
                    R.string.settings_owner_name,
                    state.ownerName.ifBlank { stringResource(R.string.settings_default_owner) },
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
      }
    }
  }
}

internal fun LazyListScope.dataSection(
    state: SettingsState,
    model: SettingsViewModel,
    navigation: SettingsNavigation,
    context: Context,
) {
  item {
    Section(stringResource(R.string.settings_data_heading)) {
      Text(
          text = stringResource(R.string.settings_export_period),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 16.dp),
      )
      PeriodFilter(
          period = state.exportPeriod.toPeriod(LocalDate.now()),
          onPreset = model::selectPreset,
          onCustom = model::selectCustom,
      )
      Action(
          R.string.settings_share_statement,
          onClick = { model.shareStatement(context) },
          enabled = !state.busy,
      )
      state.error?.let { ErrorLine(it) }
      Action(R.string.settings_trash, navigation.onOpenTrash)
      Action(R.string.settings_notifications, navigation.onOpenNotifications)
      Action(R.string.settings_support, onClick = { openSupport(context) })
    }
  }
}

internal fun LazyListScope.syncSection(
    state: SettingsState,
    model: SettingsViewModel,
    navigation: SettingsNavigation,
) {
  item {
    Section(stringResource(R.string.settings_sync_heading)) {
      Text(
          text = stringResource(R.string.settings_sync_status, syncText(state.sync)),
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(horizontal = 16.dp),
      )
      if (state.unsent > 0) {
        Text(
            text = stringResource(R.string.settings_unsent, state.unsent.toString()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
      }
      Action(R.string.settings_download_latest, model::downloadLatest)
      Action(R.string.settings_activity_log, navigation.onOpenActivityLog)
      Action(R.string.settings_recovery, navigation.onOpenRecovery)
    }
  }
}

internal fun LazyListScope.lockSection(
    state: SettingsState,
    model: SettingsViewModel,
    biometricAvailable: Boolean,
) {
  item {
    Section(stringResource(R.string.settings_lock_heading)) {
      SwitchRow(R.string.settings_lock_enable, state.lockOn, model::setLockEnabled)
      Action(
          if (state.pinSet) R.string.settings_lock_change_pin
          else R.string.settings_lock_create_pin,
          model::requestPin,
      )
      if (biometricAvailable) {
        SwitchRow(R.string.settings_lock_biometric, state.biometricEnabled, model::setBiometric)
      }
    }
  }
}

internal fun LazyListScope.appearanceSection(state: SettingsState, model: SettingsViewModel) {
  item {
    Section(stringResource(R.string.settings_appearance_heading)) {
      Row(
          modifier = Modifier.padding(horizontal = 16.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        ThemeChip(state.theme, ThemeChoice.SYSTEM, R.string.settings_theme_system, model)
        ThemeChip(state.theme, ThemeChoice.LIGHT, R.string.settings_theme_light, model)
        ThemeChip(state.theme, ThemeChoice.DARK, R.string.settings_theme_dark, model)
      }
    }
  }
}

internal fun LazyListScope.accountSection(
    state: SettingsState,
    model: SettingsViewModel,
    navigation: SettingsNavigation,
    context: Context,
    onShowStores: () -> Unit,
) {
  item {
    Section(stringResource(R.string.settings_account_heading)) {
      state.error?.takeIf { it == SettingsError.ACTION_FAILED }?.let { ErrorLine(it) }
      Action(R.string.settings_change_password, navigation.onChangePassword)
      Action(R.string.settings_saved_stores, onShowStores)
      Action(R.string.settings_sign_out, onClick = model::requestSignOut, enabled = !state.busy)
      Action(R.string.settings_remove_store, onClick = model::requestRemove, enabled = !state.busy)
      TextButton(
          onClick = { openSupport(context) },
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
      ) {
        Text(
            text = stringResource(R.string.settings_developer),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
      }
    }
  }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
    Heading(title, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    content()
  }
}

@Composable
private fun Heading(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onBackground,
) {
  Text(
      text = text,
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.Bold,
      color = color,
      modifier = modifier,
  )
}

@Composable
private fun Action(label: Int, onClick: () -> Unit, enabled: Boolean = true) {
  OutlinedButton(
      onClick = onClick,
      enabled = enabled,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
  ) {
    Text(stringResource(label))
  }
}

@Composable
private fun SwitchRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    Switch(checked = checked, onCheckedChange = onChange)
  }
}

@Composable
private fun ThemeChip(
    current: ThemeChoice,
    choice: ThemeChoice,
    label: Int,
    model: SettingsViewModel,
) {
  FilterChip(
      selected = current == choice,
      onClick = { model.setTheme(choice) },
      label = { Text(stringResource(label)) },
  )
}

@Composable
private fun ErrorLine(error: SettingsError) {
  Text(
      text =
          stringResource(
              when (error) {
                SettingsError.NOTHING_TO_EXPORT -> R.string.settings_nothing_to_export
                SettingsError.SHARE_FAILED -> R.string.settings_share_failed
                SettingsError.ACTION_FAILED -> R.string.settings_action_failed
              }
          ),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.error,
      modifier = Modifier.padding(horizontal = 16.dp),
  )
}

@Composable
private fun syncText(line: SyncLine): String =
    when (line) {
      SyncLine.Idle -> stringResource(R.string.settings_sync_idle)
      SyncLine.Running -> stringResource(R.string.settings_sync_running)
      SyncLine.Offline -> stringResource(R.string.settings_sync_offline)
      SyncLine.SignedOut -> stringResource(R.string.settings_sync_signed_out)
      SyncLine.AwaitingApproval -> stringResource(R.string.settings_sync_awaiting_approval)
      SyncLine.Revoked -> stringResource(R.string.settings_sync_revoked)
      SyncLine.Suspended -> stringResource(R.string.settings_sync_suspended)
      SyncLine.PasswordChangeRequired -> stringResource(R.string.settings_sync_password_change)
      SyncLine.Retry -> stringResource(R.string.settings_sync_retry)
      is SyncLine.Failed -> line.message
    }

/** Opens the support chat, or says it cannot (no WhatsApp installed). */
internal fun openSupport(context: Context) {
  val number = context.getString(R.string.support_whatsapp_number)
  try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number")))
  } catch (_: ActivityNotFoundException) {
    Toast.makeText(context, R.string.support_unavailable, Toast.LENGTH_SHORT).show()
  }
}
