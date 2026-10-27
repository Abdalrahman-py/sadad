package com.sadad.app.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sadad.app.BuildConfig
import com.sadad.app.R
import com.sadad.app.feature.photo.PhotoKind
import com.sadad.app.feature.photo.PhotoStore
import com.sadad.app.feature.updates.UpdateState
import com.sadad.app.ui.components.formatCents
import com.sadad.app.ui.theme.sadadPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The المتجر tab, section by section, as `boards/Store.dc.html` draws it: white cards with a 1 dp
 * line border (18 dp corners for the store card, 13 dp for the rest), 56 dp rows ending in the
 * board's 18 dp chevron, and the page's 18 dp gutter.
 */

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
    BoardCard(radius = 18.dp) {
      Row(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                    .padding(vertical = 4.dp),
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
                      ?.let { stringResource(R.string.settings_owner_line, it) }
                      ?: stringResource(R.string.settings_this_device),
              color = p.muted,
              fontSize = 13.sp,
              lineHeight = 20.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
        }
        // The board draws this 40 dp tall; the 48 dp touch target is the shell's rule.
        Surface(
            onClick = onToggleStores,
            shape = RoundedCornerShape(10.dp),
            color = p.surface,
            contentColor = linkColor(),
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

/** One saved store in the expanded switcher: its 32 dp initials block, then its name. */
@Composable
private fun StoreRow(store: SavedStore, onPick: () -> Unit) {
  val p = sadadPalette
  val name = store.storeName.ifBlank { stringResource(R.string.settings_default_store) }
  Surface(
      onClick = onPick,
      shape = RoundedCornerShape(10.dp),
      color = Color.Transparent,
      modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
  ) {
    Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Initials(name)
      Text(
          text = name,
          color = p.text,
          fontSize = 15.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/** «إضافة متجر»: the board's dashed circle with a plus, then the link-coloured label. */
@Composable
private fun AddStoreRow(onAddStore: () -> Unit) {
  val p = sadadPalette
  val link = linkColor()
  Surface(
      onClick = onAddStore,
      shape = RoundedCornerShape(10.dp),
      color = Color.Transparent,
      modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(SettingsTags.ADD_STORE),
  ) {
    Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Box(
          Modifier.size(32.dp).drawBehind {
            val stroke = 1.5.dp.toPx()
            drawCircle(
                color = p.muted,
                radius = size.minDimension / 2 - stroke / 2,
                style =
                    Stroke(
                        width = stroke,
                        pathEffect =
                            PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                    ),
            )
          },
          contentAlignment = Alignment.Center,
      ) {
        Text("+", color = link, fontSize = 16.sp, fontWeight = FontWeight.Bold)
      }
      Text(
          stringResource(R.string.settings_add_store),
          color = link,
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
    val waiting = state.unsent > 0
    Section(stringResource(R.string.settings_devices_heading)) {
      BoardCard(radius = 13.dp) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .background(if (waiting) p.creditSoft else Color.Transparent)
                    .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Box(Modifier.size(10.dp).background(statusColor(state), CircleShape))
          Column(Modifier.weight(1f)) {
            Text(
                text =
                    if (waiting) waitingText(state.unsent)
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
              onClick = navigation.onSyncNow,
              shape = RoundedCornerShape(10.dp),
              color = if (waiting) p.card else p.surface,
              contentColor = linkColor(),
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
            title = stringResource(R.string.settings_recovery),
            detail = stringResource(R.string.settings_recovery_sub),
            tag = SettingsTags.RECOVERY,
            onClick = navigation.onOpenRecovery,
            icon = RowIcon.PHONE,
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
      BoardCard(radius = 13.dp) {
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
            minHeight = 52.dp,
        )
        LinkRow(
            title = stringResource(R.string.settings_change_password),
            detail = null,
            tag = null,
            onClick = navigation.onChangePassword,
            minHeight = 52.dp,
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
  // Q20/Q6: the trash exists to undo deletes, so a device that may delete neither records nor
  // Contacts cannot use it and does not see its section at all. Statements are exported from
  // «الحركات», where the entries they list are.
  if (!state.canDelete) return
  item {
    Section(stringResource(R.string.settings_data_heading)) {
      BoardCard(radius = 13.dp) {
        LinkRow(
            title = stringResource(R.string.settings_trash),
            detail = trashText(state.trashCount),
            tag = SettingsTags.TRASH,
            onClick = navigation.onOpenTrash,
        )
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
    val p = sadadPalette
    Section(stringResource(R.string.settings_appearance_heading)) {
      // The board's group: one surface block, three equal cells, the chosen one primary.
      Row(
          modifier =
              Modifier.fillMaxWidth()
                  .background(p.surface, RoundedCornerShape(13.dp))
                  .padding(4.dp)
                  .testTag(SettingsTags.THEME),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        for ((choice, label) in
            listOf(
                ThemeChoice.SYSTEM to stringResource(R.string.settings_theme_system),
                ThemeChoice.LIGHT to stringResource(R.string.settings_theme_light),
                ThemeChoice.DARK to stringResource(R.string.settings_theme_dark),
            )) {
          val chosen = state.theme == choice
          Surface(
              onClick = { model.setTheme(choice) },
              shape = RoundedCornerShape(10.dp),
              color = if (chosen) p.primary else Color.Transparent,
              contentColor = if (chosen) Color.White else p.muted,
              modifier =
                  Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                    this.selected = chosen
                  },
          ) {
            Box(Modifier.padding(vertical = 12.dp), Alignment.Center) {
              Text(
                  label,
                  fontSize = 14.sp,
                  lineHeight = 20.sp,
                  fontWeight = FontWeight.Bold,
                  maxLines = 1,
              )
            }
          }
        }
      }
      BoardCard(radius = 13.dp) {
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
      BoardCard(radius = 13.dp) {
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
    BoardCard(radius = 13.dp, onClick = { openSupport(context) }) {
      Row(
          modifier =
              Modifier.fillMaxWidth()
                  .padding(horizontal = 14.dp, vertical = 16.dp)
                  .testTag(SettingsTags.SUPPORT),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        RowGlyph(RowIcon.CHAT)
        Text(
            stringResource(R.string.settings_support),
            color = linkColor(),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
      }
    }
  }
}

/**
 * The one reminder that cannot be dismissed. A release found days ago keeps showing here, with a
 * dot, until it is installed: the launch prompt and the notification can be snoozed, this row
 * cannot — which is what a security patch needs.
 */
internal fun LazyListScope.updateItem(update: UpdateState, onOpen: () -> Unit) {
  item {
    val version = update.manifest?.versionName
    val detail =
        when {
          version == null && update.phase == UpdateState.Phase.CHECKING ->
              stringResource(R.string.updates_row_checking)
          version == null && update.phase == UpdateState.Phase.FAILED ->
              stringResource(R.string.updates_row_failed)
          version == null && update.phase == UpdateState.Phase.UP_TO_DATE ->
              stringResource(R.string.updates_row_up_to_date, BuildConfig.VERSION_NAME)
          version == null -> stringResource(R.string.updates_row_idle)
          update.phase == UpdateState.Phase.READY ->
              stringResource(R.string.updates_row_ready, version)
          else -> stringResource(R.string.updates_row_available, version)
        }
    BoardCard(radius = 13.dp) {
      LinkRow(
          title = stringResource(R.string.updates_row_title),
          detail = detail,
          tag = SettingsTags.UPDATE,
          onClick = onOpen,
          icon = RowIcon.UPDATE,
          badge = update.manifest != null,
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
        lineHeight = 18.sp,
        textAlign = TextAlign.Center,
    )
  }
}

// --- The board's building blocks -------------------------------------------------------------

/** The board's card: `--card` white, a 1 dp `--line` border, and the given corner radius. */
@Composable
private fun BoardCard(
    radius: Dp = 13.dp,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
  val p = sadadPalette
  val shape = RoundedCornerShape(radius)
  val border = BorderStroke(1.dp, p.line)
  if (onClick == null) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = p.card,
        border = border,
    ) {
      Column(Modifier.padding(contentPadding), content = content)
    }
  } else {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = p.card,
        border = border,
    ) {
      Column(Modifier.padding(contentPadding), content = content)
    }
  }
}

/** A section title above its card: 13 sp bold muted, as the board's headings. */
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

/** The icons the board puts before a row's label; drawn here so no new drawable is needed. */
private enum class RowIcon {
  LIST,
  PHONE,
  CHAT,
  UPDATE,
}

/** A card row that leads somewhere: the label, an optional second line, then the chevron. */
@Composable
private fun LinkRow(
    title: String,
    detail: String?,
    tag: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
    minHeight: Dp = 56.dp,
    icon: RowIcon? = null,
    /** A dot after the label: there is something to do here, and it stays until it is done. */
    badge: Boolean = false,
) {
  val p = sadadPalette
  Surface(
      onClick = onClick,
      enabled = enabled,
      color = Color.Transparent,
      modifier =
          Modifier.fillMaxWidth().heightIn(min = minHeight).let { base ->
            if (tag == null) base else base.testTag(tag)
          },
  ) {
    Row(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (icon != null) RowGlyph(icon)
      Column(Modifier.weight(1f)) {
        Text(title, color = p.text, fontSize = 15.sp, lineHeight = 22.sp)
        if (detail != null) {
          Text(detail, color = p.muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
      }
      if (badge) {
        Box(Modifier.size(8.dp).background(p.primary, CircleShape))
      }
      Chevron()
    }
  }
}

/** The board's 21 dp line icons: a list, a phone and a chat bubble, stroked in the link colour. */
@Composable
private fun RowGlyph(kind: RowIcon) {
  val color = linkColor()
  Canvas(Modifier.size(21.dp)) {
    val s = size.minDimension
    val stroke = 1.9.dp.toPx()
    when (kind) {
      RowIcon.LIST -> {
        for (index in 0..2) {
          val y = s * (0.25f + 0.25f * index)
          drawLine(color, Offset(s * 0.34f, y), Offset(s * 0.9f, y), stroke, StrokeCap.Round)
          drawCircle(color, radius = stroke * 0.8f, center = Offset(s * 0.12f, y))
        }
      }
      RowIcon.PHONE -> {
        drawRoundRect(
            color = color,
            topLeft = Offset(s * 0.30f, s * 0.06f),
            size = Size(s * 0.40f, s * 0.88f),
            cornerRadius = CornerRadius(s * 0.10f),
            style = Stroke(width = stroke),
        )
        drawLine(
            color,
            Offset(s * 0.44f, s * 0.80f),
            Offset(s * 0.56f, s * 0.80f),
            stroke,
            StrokeCap.Round,
        )
      }
      RowIcon.CHAT -> {
        drawCircle(
            color = color,
            radius = s * 0.33f,
            center = Offset(s * 0.5f, s * 0.40f),
            style = Stroke(width = stroke),
        )
        // The bubble's tail, toward the bottom-start corner.
        drawLine(
            color,
            Offset(s * 0.32f, s * 0.63f),
            Offset(s * 0.20f, s * 0.88f),
            stroke,
            StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(s * 0.20f, s * 0.88f),
            Offset(s * 0.46f, s * 0.72f),
            stroke,
            StrokeCap.Round,
        )
      }
      // An arrow into a tray: the download an update is.
      RowIcon.UPDATE -> {
        drawLine(
            color,
            Offset(s * 0.5f, s * 0.08f),
            Offset(s * 0.5f, s * 0.62f),
            stroke,
            StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(s * 0.32f, s * 0.44f),
            Offset(s * 0.5f, s * 0.62f),
            stroke,
            StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(s * 0.68f, s * 0.44f),
            Offset(s * 0.5f, s * 0.62f),
            stroke,
            StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(s * 0.18f, s * 0.86f),
            Offset(s * 0.82f, s * 0.86f),
            stroke,
            StrokeCap.Round,
        )
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
      horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                // As the board draws it: the on track is the primary colour with a white thumb,
                // so it reads on a white card instead of sinking into it.
                checkedThumbColor = Color.White,
                checkedTrackColor = p.primary,
                checkedBorderColor = p.primary,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = p.line,
                uncheckedBorderColor = p.line,
            ),
    )
  }
}

/** The board's 18 dp muted chevron, pointing left: toward the screen the row opens. */
@Composable
private fun Chevron() {
  val color = sadadPalette.muted
  Canvas(Modifier.size(18.dp)) {
    val stroke = 2.dp.toPx()
    val start = Offset(size.width * 0.62f, size.height * 0.18f)
    val elbow = Offset(size.width * 0.32f, size.height * 0.5f)
    val end = Offset(size.width * 0.62f, size.height * 0.82f)
    drawLine(color, start, elbow, stroke, StrokeCap.Round)
    drawLine(color, elbow, end, stroke, StrokeCap.Round)
  }
}

/** The 32 dp soft block with a store's initials, as the switcher draws each saved store. */
@Composable
private fun Initials(name: String) {
  val p = sadadPalette
  val initials = name.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull() }
  Box(
      modifier = Modifier.size(32.dp).background(p.soft, RoundedCornerShape(10.dp)),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        initials.joinToString(""),
        color = linkColor(),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
    )
  }
}

/** The board's link colour: `--link`, the primary in light and the accent in dark. */
@Composable
internal fun linkColor(): Color {
  val p = sadadPalette
  return if (p.isDark) p.accent else p.primary
}

// --- Text ------------------------------------------------------------------------------------

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
internal fun statusPhrase(line: SyncLine): String? =
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

// --- Intents ---------------------------------------------------------------------------------

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
