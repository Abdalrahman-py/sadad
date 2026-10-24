package com.sadad.app.feature.account

import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadad.app.AppGraph
import com.sadad.app.R
import com.sadad.app.SadadApplication
import com.sadad.app.data.sync.SyncScheduler
import com.sadad.app.feature.notifications.DueReminders
import com.sadad.app.feature.photo.PhotoEditor
import com.sadad.app.feature.photo.PhotoKind
import com.sadad.app.feature.photo.PhotoStore
import com.sadad.app.ui.components.SadadLogo
import com.sadad.app.ui.components.SadadRoundIcon
import com.sadad.app.ui.theme.sadadPalette
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TopBarActions(
    screenTitle: String = "",
    onOpenNotifications: () -> Unit,
    onOpenActivityLog: () -> Unit,
    onAddStore: () -> Unit = {},
    onNeedsLogin: () -> Unit = {},
    graph: AppGraph = (LocalContext.current.applicationContext as SadadApplication).graph,
    reminders: DueReminders = DueReminders.get(LocalContext.current),
) {
  val open by graph.current.collectAsStateWithLifecycle()
  var stores by rememberSaveable { mutableStateOf(false) }
  var storesLoaded by rememberSaveable { mutableStateOf(false) }
  val store = open
  // Starting a new composition for another store prevents one frame of its predecessor's
  // badge/photo.
  if (store != null)
      key(store.account.storeUserId) {
        StoreTopBar(
            graph,
            reminders,
            store.account.storeUserId,
            store.account.storeName,
            screenTitle,
            onOpenNotifications,
            onOpenActivityLog,
            onStores = {
              storesLoaded = true
              stores = true
            },
        )
      }
  // Switching closes current before the settings model can emit NEEDS_LOGIN.
  // Keep its observer alive even while there is no open store or the account key changes.
  if (storesLoaded)
      SavedStoresController(graph, stores, { stores = false }, onAddStore, onNeedsLogin)
}

@Composable
private fun StoreTopBar(
    graph: AppGraph,
    reminders: DueReminders,
    storeUserId: String,
    storeName: String,
    screenTitle: String,
    onOpenNotifications: () -> Unit,
    onOpenActivityLog: () -> Unit,
    onStores: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val photos = remember(context) { PhotoStore.get(context) }
  val revision by photos.changes.collectAsStateWithLifecycle()
  val portrait by
      produceState<Bitmap?>(null, storeUserId, revision) {
        value =
            try {
              photos.load(storeUserId, PhotoKind.ACCOUNT)
            } catch (_: IOException) {
              null
            }
      }
  val unread by
      remember(reminders, storeUserId) { reminders.unreadCount(storeUserId) }
          .collectAsStateWithLifecycle(0)
  var menu by rememberSaveable { mutableStateOf(false) }
  var photo by rememberSaveable { mutableStateOf(false) }
  var appearance by rememberSaveable { mutableStateOf(false) }
  val avatarDescription = stringResource(R.string.account_avatar)
  val p = sadadPalette
  val bell = stringResource(R.string.account_bell)
  Column(
      // The Scaffold hands the bars to content, not to its top bar slot, so the header clears the
      // status bar and the sides itself, the insets a TopAppBar takes by default.
      modifier =
          Modifier.background(p.bg)
              .windowInsetsPadding(
                  WindowInsets.systemBars
                      .union(WindowInsets.displayCutout)
                      .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
              )
  ) {
    // The Java header: logo, «سدد» over the screen's name, then the bell and the account portrait.
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      SadadLogo(42.dp, dark = isSystemInDarkTheme())
      Spacer(Modifier.width(10.dp))
      Column(Modifier.weight(1f)) {
        Text(
            stringResource(R.string.brand_name),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
            color = if (isSystemInDarkTheme()) p.accent else p.primary,
            maxLines = 1,
        )
        Text(
            screenTitle.ifEmpty {
              storeName.ifBlank { stringResource(R.string.account_default_store) }
            },
            style = MaterialTheme.typography.bodySmall,
            color = p.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
      }
      SadadRoundIcon(
          painterResource(R.drawable.ic_notifications),
          bell,
          onOpenNotifications,
          badge = unread,
      )
      Spacer(Modifier.width(6.dp))
      Box(
          modifier =
              Modifier.size(38.dp)
                  .clip(CircleShape)
                  .background(p.soft)
                  .clickable(role = Role.Button) { menu = true }
                  .semantics { contentDescription = avatarDescription },
          contentAlignment = Alignment.Center,
      ) {
        val image = portrait
        if (image == null)
            Icon(
                painterResource(R.drawable.ic_person),
                contentDescription = null,
                tint = if (isSystemInDarkTheme()) p.text else p.primary,
                modifier = Modifier.size(22.dp),
            )
        else
            Image(
                image.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(38.dp).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
      }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.line.copy(alpha = 72 / 255f)))
  }
  if (menu) {
    AccountMenu(
        storeName = storeName,
        onPhoto = { photo = true },
        onAppearance = { appearance = true },
        onNotificationSettings = {
          context.startActivity(
              Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                  .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
          )
        },
        onStores = onStores,
        onSync = {
          scope.launch {
            withContext(Dispatchers.IO) { SyncScheduler(context).syncNow(storeUserId) }
          }
        },
        onActivityLog = onOpenActivityLog,
        onDismiss = { menu = false },
    )
  }
  if (photo)
      PhotoEditor(storeUserId, PhotoKind.ACCOUNT, { photo = false }) {
        graph.current.value?.account?.storeUserId
      }
  if (appearance) AppearanceDialog { appearance = false }
}
