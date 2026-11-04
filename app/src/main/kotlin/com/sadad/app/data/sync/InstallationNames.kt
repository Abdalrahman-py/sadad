package com.sadad.app.data.sync

import com.sadad.app.data.db.LedgerDatabase
import com.sadad.app.data.db.toName
import com.sadad.ledger.InstallationName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The store's latest device names, keyed by installation id. Sync rewrites them with every
 * `changes` page; screens read this read-only.
 */
fun LedgerDatabase.installationNames(): Flow<Map<String, InstallationName>> =
    installationNameDao().observe().map { rows -> rows.associate { it.id to it.toName() } }
