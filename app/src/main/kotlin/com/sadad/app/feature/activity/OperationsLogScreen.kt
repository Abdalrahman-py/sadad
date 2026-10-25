package com.sadad.app.feature.activity

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sadad.app.R
import com.sadad.app.SadadApplication
import com.sadad.app.feature.history.ActivityLogViewModel
import com.sadad.app.feature.history.ActivityTab
import com.sadad.app.ui.components.ScreenHeader

/** The ledger operations log, opened from Store's «سجل عمليات الدفتر». */
@Composable
fun OperationsLogScreen(
    onBack: () -> Unit,
    activityViewModel: ActivityLogViewModel = operationsLogViewModel(),
) {
  val activity by activityViewModel.state.collectAsStateWithLifecycle()
  Column(Modifier.fillMaxSize()) {
    ScreenHeader(stringResource(R.string.settings_activity_log), onBack = onBack)
    ActivityTab(activity = activity, onDismiss = activityViewModel::dismiss)
  }
}

@Composable
private fun operationsLogViewModel(): ActivityLogViewModel {
  val app = LocalContext.current.applicationContext as SadadApplication
  return viewModel(factory = viewModelFactory { initializer { ActivityLogViewModel(app.graph) } })
}
