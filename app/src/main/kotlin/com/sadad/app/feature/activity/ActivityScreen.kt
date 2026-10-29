package com.sadad.app.feature.activity

import androidx.compose.runtime.Composable
import com.sadad.app.feature.history.HistoryScreen

/** The recent debt and payment feed, with period and order filters. */
@Composable
fun ActivityScreen(onOpenContact: (String) -> Unit) {
  HistoryScreen(onOpenContact = onOpenContact)
}
