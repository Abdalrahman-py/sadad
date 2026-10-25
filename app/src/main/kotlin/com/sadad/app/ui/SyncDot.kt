package com.sadad.app.ui

import com.sadad.app.AppGraph
import com.sadad.app.data.ledger.OutboxState
import com.sadad.app.data.sync.SyncStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** The dot on the المتجر tab: none, amber when entries wait to send, red when offline. */
enum class SyncDot {
  NONE,
  PENDING,
  OFFLINE,
}

/** Read from what the sync layer already exposes: the open store's outbox and sync status. */
@OptIn(ExperimentalCoroutinesApi::class)
fun syncDot(graph: AppGraph): Flow<SyncDot> =
    graph.current
        .flatMapLatest { open ->
          if (open == null) flowOf(SyncDot.NONE)
          else
              combine(open.outbox.rows, graph.syncStatus(open.account.storeUserId)) { rows, status
                ->
                when {
                  status == SyncStatus.Offline -> SyncDot.OFFLINE
                  rows.any { it.state == OutboxState.QUEUED } -> SyncDot.PENDING
                  else -> SyncDot.NONE
                }
              }
        }
        .distinctUntilChanged()
