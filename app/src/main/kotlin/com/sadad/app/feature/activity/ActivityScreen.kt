package com.sadad.app.feature.activity

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sadad.app.R
import com.sadad.app.feature.history.HistoryScreen

/**
 * The الحركات tab: the history body under the Activity board's top bar (its title and the export
 * pill). The per-entry feed and the period totals wait for ticket 85.
 */
@Composable
fun ActivityScreen(onOpenContact: (String) -> Unit) {
  HistoryScreen(onOpenContact = onOpenContact, title = stringResource(R.string.nav_activity))
}
