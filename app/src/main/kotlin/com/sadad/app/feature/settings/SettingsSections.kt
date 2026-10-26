package com.sadad.app.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sadad.app.BuildConfig
import com.sadad.app.R
import com.sadad.app.feature.history.PeriodFilter
import com.sadad.app.feature.photo.PhotoKind
import com.sadad.app.feature.photo.PhotoStore
import com.sadad.app.ui.components.Avatar
import com.sadad.app.ui.components.SadadButton
import com.sadad.app.ui.components.SadadButtonStyle
import com.sadad.app.ui.components.SadadCard
import com.sadad.app.ui.components.SadadSegments
import com.sadad.app.ui.components.formatCents
import com.sadad.app.ui.theme.sadadPalette
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The sections in the order the board draws them. Each one is part of the screen's single
// LazyColumn, so the whole tab scrolls as one page.

internal fun LazyListScope.storeCard(
    state: SettingsState,
    model: SettingsViewModel,
    storesOpen: Boolean,
    onToggleStores: () -> Unit,
    onOpenPhoto: () -> Unit,
    onAddStore: () -> Unit,
    onPickStore: () -> Unit,
) {
  val open = state.accounts.firstOrNull { it.active }?.storeUserId
  item {
    val p = sadadPalette
    SadadCard(contentPadding = PaddingValues(0.dp)) {
      Row(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        StorePhoto(open, onOpenPhoto)
        val description = stringResource(R.string.settings_edit_profile_description)
        Column(
            modifier =
                Modifier.weight(1f)
                    .heightIn(min = 48.dp)
                    .clickable(onClickLabel = description, role = Role.Button) {
                      model.editProfile()
                    }
                    .semantics(mergeDescendants = true) {}
                    .testTag(SettingsTags.PROFILE)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.Center,
        ) {
          Text(
              text = state.storeName.ifBlank { stringResource(R.string.settings_default_store) },
              color = p.text,
              fontSize = 17.sp,
              lineHeight = 24.sp,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
          Text(
              // The owner as the account knows it; the part it does not know is left out.
              text =
                  state.ownerName
                      .ifBlank { null }
                      ?.let {
                        stringResource(R.string.settings_owner_line, it)
                      } ?: stringResource(R.string.settings_this_device),
              color = p.muted,
              fontSize = 13.sp,
              lineHeight = 20.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
        }
        Surface(
            onClick = onToggleStores,
            shape = RoundedCornerShape(10.dp),
            color = p.surface,
            contentColor = if (p.isDark) p.accent else p.primary,
            modifier = Modifier.heightIn(min = 48.dp).testTag(SettingsTags.SWITCHER),
        ) {
          Box(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), Alignment.Center) {
            Text(
                stringResource(R.string.settings_switcher),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
          }
        }
      }
      if (storesOpen) {
        HorizontalDivider(color = p.line)
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          for (store in state.accounts) {
            StoreRow(
                store,
                onPick = {
                  if (!store.active) model.switchTo(store.storeUserId)
                  onPickStore()
                },
            )
          }
          AddStoreRow(
              onAddStore = {
                onPickStore()
                onAddStore()
              }
          )
        }
      }
    }
  }
}

/** The store's photo, or the shop glyph when it has none; tapping it opens the photo editor. */
@Composable
private fun StorePhoto(storeUserId: String?, onOpenPhoto: () -> Unit) {
  val p = sadadPalette
  val context = LocalContext.current
  val photos = remember(context) { PhotoStore.get(context) }
  val revision by photos.changes.collectAsState()
  var bitmap by remember(storeUserId) { mutableStateOf<Bitmap?>(null) }
  LaunchedEffect(storeUserId, revision) {
    bitmap = storeUserId?.let { id ->
      withContext(Dispatchers.IO) { photos.load(id, PhotoKind.STORE) }
    }
  }
  val description = stringResource(R.string.photo_store)
  Box(
      modifier =
          Modifier.size(48.dp)
              .clip(RoundedCornerShape(14.dp))
              .background(p.primary)
              .clickable(enabled = storeUserId != null, role = Role.Button) {
                if (storeUserId != null) onOpenPhoto()
              }
              .semantics { contentDescription = description }
              .testTag(SettingsTags.PHOTO),
      contentAlignment = Alignment.Center,
  ) {
    val image = bitmap
    if (image != null) {
      Image(
          bitmap = image.asImageBitmap(),
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
      )
    } else {
      Icon(
          painterResource(R.drawable.ic_store),
          contentDescription = null,
          tint = Color.White,
          modifier = Modifier.size(24.dp),
      )
    }
  }
}

@Composable
private fun StoreRow(store: SavedStore, onPick: () -> Unit) {
  val p = sadadPalette
  Surface(
      onClick = onPick,
      color = Color.Transparent,
      modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
  ) {
    Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Avatar(store.storeName.ifBlank { stringResource(R.string.settings_default_store) })
      Text(
          text = store.storeName.ifBlank { stringResource(R.string.settings_default_store) },
          color = p.text,
          fontSize = 15.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun AddStoreRow(onAddStore: () -> Unit) {
  val p = sadadPalette
  Surface(
      onClick = onAddStore,
      color = Color.Transparent,
      modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(SettingsTags.ADD_STORE),
  ) {
    Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Box(
          Modifier.size(32.dp).background(p.soft, RoundedCornerShape(10.dp)),
          contentAlignment = Alignment.Center,
      ) {
        Text("+", color = p.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
      }
      Text(
          stringResource(R.string.settings_add_store),
          color = if (p.isDark) p.accent else p.primary,
          fontSize = 15.sp,
          fontWeight = FontWeight.Bold,
      )
    }
  }
}

internal fun LazyListScope.devicesSection(
    state: SettingsState,
    model: SettingsViewModel,
    navigation: SettingsNavigation,
) {
  item {
    val p = sadadPalette
    Section(stringResource(R.string.settings_devices_heading)) {
      SadadCard(contentPadding = PaddingValues(0.dp)) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .background(if (state.unsent > 0) p.creditSoft else Color.Transparent)
                    .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Box(Modifier.size(10.dp).background(statusColor(state), CircleShape))
          Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                text =
                    if (state.unsent > 0) waitingText(state.unsent)
                    else stringResource(R.string.settings_sync_all_sent),
                color = p.text,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            syncDetail(state)?.let { detail ->
              Text(detail, color = p.muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
          }
          Surface(
              onClick = model::downloadLatest,
              shape = RoundedCornerShape(10.dp),
              color = p.card,
              contentColor = if (p.isDark) p.accent else p.primary,
              modifier = Modifier.heightIn(min = 48.dp).testTag(SettingsTags.SYNC_NOW),
          ) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), Alignment.Center) {
              Text(
                  stringResource(R.string.settings_sync_now),
                  fontSize = 13.sp,
                  fontWeight = FontWeight.Bold,
              )
            }
          }
        }
        HorizontalDivider(color = p.line)
        LinkRow(
            title = stringResource(R.string.settings_activity_log),
            detail = stringResource(R.string.settings_activity_log_sub),
            tag = SettingsTags.OPS_LOG,
            onClick = navigation.onOpenActivityLog,
        )
        HorizontalDivider(color = p.line)
        LinkRow(
            title = stringResource(R.string.settings_recovery),
            detail = stringResource(R.string.settings_recovery_sub),
            tag = SettingsTags.RECOVERY,
            onClick = navigation.onOpenRecovery,
        )
      }
    }
  }
}

internal fun LazyListScope.securitySection(
    state: SettingsState,
    model: SettingsViewModel,
    navigation: SettingsNavigation,
) {
  item {
    val p = sadadPalette
    Section(stringResource(R.string.settings_security_heading)) {
      SadadCard(contentPadding = PaddingValues(0.dp)) {
        SwitchLine(
            title = stringResource(R.string.settings_lock_enable),
            detail = stringResource(R.string.settings_lock_enable_sub),
            checked = state.lockOn,
            onChange = model::setLockEnabled,
        )
        if (state.biometricAvailable) {
          HorizontalDivider(color = p.line)
          SwitchLine(
              title = stringResource(R.string.settings_lock_biometric),
              detail = stringResource(R.string.settings_lock_biometric_sub),
              checked = state.biometricEnabled,
              onChange = model::setBiometric,
          )
        }
        HorizontalDivider(color = p.line)
        LinkRow(
            title =
                stringResource(
                    if (state.pinSet) R.string.settings_lock_change_pin
                    else R.string.settings_lock_create_pin
                ),
            detail = null,
            tag = null,
            onClick = model::requestPin,
        )
        HorizontalDivider(color = p.line)
        LinkRow(
            title = stringResource(R.string.settings_change_password),
            detail = null,
            tag = null,
            onClick = navigation.onChangePassword,
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
    val p = sadadPalette
    Section(stringResource(R.string.settings_data_heading)) {
      SadadCard(contentPadding = PaddingValues(0.dp)) {
        // Q20/Q6: the trash exists to undo deletes, so a device that may delete neither records nor
        // Contacts cannot use it and does not see it.
        if (state.canDelete) {
          LinkRow(
              title = stringResource(R.string.settings_trash),
              detail = trashText(state.trashCount),
              tag = SettingsTags.TRASH,
              onClick = navigation.onOpenTrash,
          )
          HorizontalDivider(color = p.line)
        }
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
              stringResource(R.string.settings_export_period),
              color = p.muted,
              fontSize = 12.sp,
              lineHeight = 18.sp,
          )
          PeriodFilter(
              period = state.exportPeriod.toPeriod(LocalDate.now()),
              onPreset = model::selectPreset,
              onCustom = model::selectCustom,
          )
          SadadButton(
              text = stringResource(R.string.settings_share_statement),
              style = SadadButtonStyle.SURFACE,
              onClick = { model.shareStatement(context) },
              enabled = !state.busy,
              modifier = Modifier.fillMaxWidth(),
          )
          state.error?.takeIf { it != SettingsError.ACTION_FAILED }?.let { ErrorLine(it) }
        }
      }
    }
  }
}

internal fun LazyListScope.appearanceSection(
    state: SettingsState,
    model: SettingsViewModel,
    context: Context,
) {
  item {
    Section(stringResource(R.string.settings_appearance_heading)) {
      SadadSegments(
          options =
              listOf(
                  ThemeChoice.SYSTEM to stringResource(R.string.settings_theme_system),
                  ThemeChoice.LIGHT to stringResource(R.string.settings_theme_light),
                  ThemeChoice.DARK to stringResource(R.string.settings_theme_dark),
              ),
          selected = state.theme,
          onSelect = model::setTheme,
          modifier = Modifier.testTag(SettingsTags.THEME),
      )
      SadadCard(contentPadding = PaddingValues(0.dp)) {
        LinkRow(
            title = stringResource(R.string.settings_notification_settings),
            detail = stringResource(R.string.settings_notification_settings_sub),
            tag = SettingsTags.NOTIFICATIONS,
            onClick = { openNotificationSettings(context) },
        )
      }
    }
  }
}

internal fun LazyListScope.accountSection(state: SettingsState, model: SettingsViewModel) {
  item {
    val p = sadadPalette
    Section(stringResource(R.string.settings_account_heading)) {
      SadadCard(contentPadding = PaddingValues(0.dp)) {
        LinkRow(
            title = stringResource(R.string.settings_sign_out),
            detail = null,
            tag = SettingsTags.SIGN_OUT,
            onClick = model::requestSignOut,
            enabled = !state.busy,
        )
        HorizontalDivider(color = p.line)
        LinkRow(
            title = stringResource(R.string.settings_remove_store),
            detail = null,
            tag = SettingsTags.REMOVE,
            onClick = model::requestRemove,
            enabled = !state.busy,
        )
        state.error
            ?.takeIf { it == SettingsError.ACTION_FAILED }
            ?.let {
              Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) { ErrorLine(it) }
            }
      }
    }
  }
}

internal fun LazyListScope.supportItem(context: Context) {
  item {
    val p = sadadPalette
    SadadCard(
        onClick = { openSupport(context) },
        contentPadding = PaddingValues(16.dp),
        modifier = Modifier.testTag(SettingsTags.SUPPORT),
    ) {
      Text(
          stringResource(R.string.settings_support),
          color = if (p.isDark) p.accent else p.primary,
          fontSize = 15.sp,
          fontWeight = FontWeight.Bold,
      )
    }
  }
}

internal fun LazyListScope.versionItem() {
  item {
    Text(
        text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
        modifier = Modifier.fillMaxWidth().testTag(SettingsTags.VERSION),
        color = sadadPalette.muted,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
    )
  }
}

/** A section title above its card, as the board draws the 13sp muted headers. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
        text = title,
        modifier = Modifier.semantics { heading() },
        color = sadadPalette.muted,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Bold,
    )
    content()
  }
}

/** A card row that leads somewhere: the label, an optional second line. */
@Composable
private fun LinkRow(
    title: String,
    detail: String?,
    tag: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
  val p = sadadPalette
  Surface(
      onClick = onClick,
      enabled = enabled,
      color = Color.Transparent,
      modifier =
          Modifier.fillMaxWidth().heightIn(min = 56.dp).let { base ->
            if (tag == null) base else base.testTag(tag)
          },
  ) {
    Column(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
      Text(title, color = p.text, fontSize = 15.sp, lineHeight = 22.sp)
      if (detail != null) {
        Text(detail, color = p.muted, fontSize = 12.sp, lineHeight = 18.sp)
      }
    }
  }
}

/** A labelled switch: the whole row toggles, the switch only draws the state. */
@Composable
private fun SwitchLine(
    title: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
  val p = sadadPalette
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .heightIn(min = 56.dp)
              .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
              .padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, color = p.text, fontSize = 15.sp, lineHeight = 22.sp)
      Text(detail, color = p.muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
    Switch(
        checked = checked,
        onCheckedChange = null,
        colors =
            SwitchDefaults.colors(
                checkedThumbColor = p.primary,
                checkedTrackColor = p.soft,
                checkedBorderColor = p.soft,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = p.line,
                uncheckedBorderColor = p.line,
            ),
    )
  }
}

/** The second line under the waiting count: the newest entry, then what sync is doing. */
@Composable
private fun syncDetail(state: SettingsState): String? {
  val phrase = statusPhrase(state.sync)
  val parts = buildList {
    state.newest?.let { add(queuedText(it)) }
    when {
      phrase != null -> add(phrase)
      // Nothing special to report: say the queue sends itself.
      state.unsent > 0 -> add(stringResource(R.string.settings_sync_auto))
    }
  }
  return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun queuedText(entry: QueuedEntry): String {
  val amount = entry.amount?.let(::formatCents).orEmpty()
  return when (entry.kind) {
    QueuedKind.DEBT -> stringResource(R.string.settings_queued_debt, entry.name, amount)
    QueuedKind.DEBT_CORRECT ->
        stringResource(R.string.settings_queued_debt_correct, entry.name, amount)
    QueuedKind.PAYMENT -> stringResource(R.string.settings_queued_payment, entry.name, amount)
    QueuedKind.PAYMENT_REVERSE ->
        stringResource(R.string.settings_queued_payment_reverse, entry.name)
    QueuedKind.CONTACT_NEW -> stringResource(R.string.settings_queued_contact_new, entry.name)
    QueuedKind.CONTACT_EDIT -> stringResource(R.string.settings_queued_contact_edit, entry.name)
    QueuedKind.CONTACT_ARCHIVE ->
        stringResource(R.string.settings_queued_contact_archive, entry.name)
    QueuedKind.CONTACT_RESTORE ->
        stringResource(R.string.settings_queued_contact_restore, entry.name)
  }
}

/** What sync is doing, when it is not the ordinary «sent» or «waiting» case. */
@Composable
private fun statusPhrase(line: SyncLine): String? =
    when (line) {
      SyncLine.Idle -> null
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

@Composable
private fun statusColor(state: SettingsState): Color {
  val p = sadadPalette
  return when (state.sync) {
    SyncLine.SignedOut,
    SyncLine.Revoked,
    SyncLine.Suspended,
    SyncLine.PasswordChangeRequired,
    is SyncLine.Failed -> p.debt
    else -> if (state.unsent > 0 || state.sync == SyncLine.Offline) p.warn else p.primary
  }
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
      color = sadadPalette.debt,
      fontSize = 12.sp,
  )
}

/**
 * Arabic counts written out in code, not by [pluralStringResource]: Android picks the plural form
 * from the phone's locale, and an English phone would say «2 إدخال» for a screen that is Arabic.
 */
@Composable
private fun waitingText(count: Int): String =
    when {
      count == 1 -> stringResource(R.string.settings_sync_waiting_one)
      count == 2 -> stringResource(R.string.settings_sync_waiting_two)
      count in 3..10 -> stringResource(R.string.settings_sync_waiting_few, count)
      else -> stringResource(R.string.settings_sync_waiting_many, count)
    }

@Composable
private fun trashText(count: Int): String {
  val items =
      when {
        count <= 0 -> stringResource(R.string.settings_trash_none)
        count == 1 -> stringResource(R.string.settings_trash_one)
        count == 2 -> stringResource(R.string.settings_trash_two)
        count in 3..10 -> stringResource(R.string.settings_trash_few, count)
        else -> stringResource(R.string.settings_trash_many, count)
      }
  return "$items · ${stringResource(R.string.settings_trash_hint)}"
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

/** Android's own per-app notification settings, where sound and vibration live. */
internal fun openNotificationSettings(context: Context) {
  try {
    context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    )
  } catch (_: ActivityNotFoundException) {
    Toast.makeText(context, R.string.settings_notification_settings_failed, Toast.LENGTH_SHORT)
        .show()
  }
}
