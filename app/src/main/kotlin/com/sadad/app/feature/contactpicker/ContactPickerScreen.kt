package com.sadad.app.feature.contactpicker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sadad.app.SadadApplication
import com.sadad.app.contactPeople
import com.sadad.app.ui.components.sadadPageBackground
import kotlinx.coroutines.flow.filterNotNull

/**
 * The form's «من جهات الاتصال». It opens Android's own picker at once and draws nothing of its own:
 * the owner picks one number there, and one [PickedContact] goes back to the caller. Backing out of
 * the picker goes back to the form.
 */
@Composable
fun ContactPickerScreen(
    viewModel: ContactPickerViewModel,
    onResult: (PickedContact) -> Unit,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val open = rememberPhoneContactPicker { contact ->
    if (contact == null) onBack() else viewModel.onDeviceContact(contact)
  }
  // Saved, so a rotation while the system picker is up does not open a second one.
  var opened by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    if (!opened) {
      opened = true
      open()
    }
  }
  LaunchedEffect(state.picked) {
    val picked = state.picked ?: return@LaunchedEffect
    viewModel.pickDelivered()
    onResult(picked)
  }
  Box(modifier.fillMaxSize().sadadPageBackground())
}

/** The route-side entry: the store's people off [SadadApplication]'s graph. */
@Composable
fun ContactPickerScreen(
    onResult: (PickedContact) -> Unit,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
  ContactPickerScreen(
      viewModel =
          viewModel(
              factory =
                  viewModelFactory {
                    initializer {
                      val app = this[APPLICATION_KEY] as SadadApplication
                      ContactPickerViewModel(app.graph.contactPeople().filterNotNull())
                    }
                  },
          ),
      onResult = onResult,
      onBack = onBack,
      modifier = modifier,
  )
}
