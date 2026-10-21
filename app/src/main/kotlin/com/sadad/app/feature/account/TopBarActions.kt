package com.sadad.app.feature.account

import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadad.app.AppGraph
import com.sadad.app.R
import com.sadad.app.SadadApplication
import com.sadad.app.data.sync.SyncScheduler
import com.sadad.app.feature.notifications.DueReminders
import com.sadad.app.feature.photo.PhotoEditor
import com.sadad.app.feature.photo.PhotoKind
import com.sadad.app.feature.photo.PhotoStore
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TopBarActions(
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
  Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
  ) {
    IconButton(
        onClick = { menu = true },
        modifier = Modifier.semantics { contentDescription = avatarDescription },
    ) {
      val image = portrait
      if (image == null) Icon(painterResource(R.drawable.ic_person), contentDescription = null)
      else
          Image(
              image.asImageBitmap(),
              contentDescription = null,
              modifier = Modifier.size(38.dp).clip(CircleShape),
          )
    }
    Text(
        storeName.ifBlank { stringResource(R.string.account_default_store) },
        modifier = Modifier.weight(1f),
    )
    IconButton(onClick = onOpenNotifications) {
      BadgedBox(badge = { if (unread > 0) Badge { Text(unread.toString()) } }) {
        Icon(painterResource(R.drawable.ic_notifications), stringResource(R.string.account_bell))
      }
    }
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
