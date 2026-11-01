package com.sadad.app.feature.trash

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sadad.app.R
import com.sadad.app.SadadApplication
import com.sadad.app.feature.notifications.DueReminders
import com.sadad.app.requireGraph
import com.sadad.app.ui.components.Avatar
import com.sadad.app.ui.components.ConfirmDialog
import com.sadad.app.ui.components.EmptyState
import com.sadad.app.ui.components.SadadButton
import com.sadad.app.ui.components.SadadButtonStyle
import com.sadad.app.ui.components.ScreenHeader
import com.sadad.app.ui.components.formatCents
import com.sadad.app.ui.theme.sadadPalette

@Composable
fun TrashScreen(onBack: () -> Unit) {
  Column(Modifier.fillMaxSize()) {
    ScreenHeader(stringResource(R.string.title_trash), onBack = onBack)
    TrashScreen(trashViewModel(), Modifier.weight(1f))
  }
}

/** The Trash board: the intro, one card per archived item with «استعادة», and the toast. */
@Composable
fun TrashScreen(viewModel: TrashViewModel, modifier: Modifier = Modifier) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val p = sadadPalette

  Box(modifier.fillMaxSize()) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      item(key = "intro") {
        Text(
            stringResource(R.string.trash_subtitle),
            color = p.muted,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
      }
      if (state.items.isEmpty()) {
        item(key = "empty") {
          EmptyState(
              title = stringResource(R.string.trash_empty_title),
              detail = stringResource(R.string.trash_empty_detail),
              icon = R.drawable.ic_archive,
              modifier = Modifier.fillMaxWidth(),
          )
        }
      } else {
        items(state.items, key = { it.id }) { item ->
          TrashRow(
              item,
              restoring = item.id in state.restoring,
              onRestore = { viewModel.restore(item) },
              onDelete = { viewModel.askDelete(item) },
              modifier = Modifier.animateItem(),
          )
        }
      }
    }
    // The board's dark toast after a restore or a refusal.
    AnimatedVisibility(
        visible = state.message != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp),
    ) {
      Text(
          state.message.orEmpty(),
          color = Color.White,
          fontSize = 14.sp,
          modifier =
              Modifier.fillMaxWidth()
                  .background(TOAST, RoundedCornerShape(13.dp))
                  .padding(horizontal = 14.dp, vertical = 12.dp)
                  .semantics { liveRegion = LiveRegionMode.Polite },
      )
    }
  }

  state.confirm?.let { item ->
    ConfirmDialog(
        title = stringResource(R.string.trash_confirm_title),
        message = stringResource(R.string.trash_confirm_message),
        confirmText = stringResource(R.string.trash_confirm_delete),
        dismissText = stringResource(R.string.trash_cancel),
        onConfirm = { viewModel.deleteForGood(item) },
        onDismiss = viewModel::dismissDelete,
        destructive = true,
    )
  }
}

@Composable
private fun TrashRow(
    item: TrashItem,
    restoring: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val p = sadadPalette
  Column(
      modifier
          .fillMaxWidth()
          .background(p.card, RoundedCornerShape(13.dp))
          .border(1.dp, p.line, RoundedCornerShape(13.dp))
          .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Avatar(item.title())
      Column(Modifier.weight(1f)) {
        Text(
            item.title(),
            color = p.text,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(item.detail(), color = p.muted, fontSize = 12.sp, lineHeight = 18.sp)
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SadadButton(
          text = stringResource(R.string.trash_restore),
          onClick = onRestore,
          enabled = !restoring,
          height = 44.dp,
          modifier = Modifier.weight(1f),
      )
      SadadButton(
          text = stringResource(R.string.trash_delete),
          onClick = onDelete,
          style = SadadButtonStyle.TEXT,
          enabled = !restoring,
          height = 44.dp,
      )
    }
  }
}

private val TOAST = Color(0xFF142C24)

@Composable
private fun TrashItem.title(): String =
    when (this) {
      is TrashItem.Contact -> contact.name
      is TrashItem.Notification -> reminder.contactName
    }

@Composable
private fun TrashItem.detail(): String =
    when (this) {
      is TrashItem.Contact ->
          stringResource(R.string.trash_kind_contact) +
              if (contact.phone.isBlank()) "" else " · ${contact.phone}"
      is TrashItem.Notification ->
          stringResource(R.string.trash_kind_notification) +
              " · ${formatCents(reminder.amountCents)} · ${reminder.dueDate}"
    }

@Composable
private fun trashViewModel(): TrashViewModel {
  val graph = requireGraph()
  return viewModel(
      factory =
          viewModelFactory {
            initializer {
              val app = this[APPLICATION_KEY] as SadadApplication
              val store = checkNotNull(graph.current.value) { "the trash needs an open store" }
              val storeUserId = store.account.storeUserId
              TrashViewModel(
                  store.repository,
                  store.outbox,
                  DueReminders.get(app),
                  TrashHidden.get(app, storeUserId),
                  storeUserId,
                  app,
              )
            }
          }
  )
}
