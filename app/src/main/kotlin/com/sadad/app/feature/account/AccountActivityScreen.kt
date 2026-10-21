package com.sadad.app.feature.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sadad.app.SadadApplication
import com.sadad.app.feature.history.HistoryScreen
import com.sadad.app.feature.history.HistoryTab
import com.sadad.app.feature.history.HistoryViewModel

/** Open the existing History route on its activity tab, once for each account-menu request. */
@Composable
fun AccountActivityScreen(request: Int) {
  val app = LocalContext.current.applicationContext as SadadApplication
  val model: HistoryViewModel =
      viewModel(
          factory =
              viewModelFactory {
                initializer { HistoryViewModel(app.graph, createSavedStateHandle()) }
              }
      )
  var applied by rememberSaveable { mutableIntStateOf(0) }
  LaunchedEffect(request) {
    if (request != applied) {
      model.onTabChange(HistoryTab.ACTIVITY)
      applied = request
    }
  }
  HistoryScreen(historyViewModel = model)
}
