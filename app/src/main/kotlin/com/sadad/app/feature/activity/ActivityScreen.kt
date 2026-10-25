package com.sadad.app.feature.activity

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.sadad.app.R
import com.sadad.app.feature.history.HistoryScreen
import com.sadad.app.ui.components.ScreenHeader

/** The الحركات tab. Until ticket 85 redesigns it, the existing history body under a header. */
@Composable
fun ActivityScreen(onOpenContact: (String) -> Unit) {
  Column(Modifier.fillMaxSize()) {
    ScreenHeader(stringResource(R.string.nav_activity))
    HistoryScreen(onOpenContact = onOpenContact)
  }
}
