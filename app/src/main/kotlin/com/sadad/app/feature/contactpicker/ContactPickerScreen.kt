package com.sadad.app.feature.contactpicker

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sadad.app.R
import com.sadad.app.SadadApplication
import com.sadad.app.contactPeople
import com.sadad.app.ui.components.SadadButton
import com.sadad.app.ui.components.SadadButtonStyle
import com.sadad.app.ui.components.SadadField
import com.sadad.app.ui.components.SadadPageHeading
import kotlinx.coroutines.flow.filterNotNull

/**
 * The contacts list the form's «من جهات الاتصال» opens. Everything stays on the phone: the query
 * result is shown, never saved, logged or uploaded; picking one contact hands one [PickedContact]
 * to the caller. The permission is asked only here, once the owner taps «متابعة»; a «don't ask
 * again» denial opens the app's settings page.
 */
@Composable
fun ContactPickerScreen(
    viewModel: ContactPickerViewModel,
    onResult: (PickedContact) -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val readContacts = Manifest.permission.READ_CONTACTS
  val activity = remember(context) { context.findActivity() }
  val launcher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val canAskAgain =
            activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, readContacts) }
                ?: true
        viewModel.onPermissionResult(granted, canAskAgain)
      }

  Column(
      modifier = modifier.fillMaxSize().padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SadadPageHeading(
        title = stringResource(R.string.contactpicker_title),
        onBack = onBack,
    )
    when (val permission = state.permission) {
      ContactPermission.NotAsked -> {
        Text(
            stringResource(R.string.contactpicker_explain),
            style = MaterialTheme.typography.bodyLarge,
        )
        SadadButton(
            text = stringResource(R.string.contactpicker_continue),
            onClick = { launcher.launch(readContacts) },
            modifier = Modifier.fillMaxWidth(),
        )
        SadadButton(
            text = stringResource(R.string.contactpicker_not_now),
            style = SadadButtonStyle.SURFACE,
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        )
      }
      ContactPermission.Denied,
      ContactPermission.DeniedForever -> {
        Text(
            stringResource(R.string.contactpicker_denied),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (permission is ContactPermission.DeniedForever) {
          OpenSettingsButton(context)
        }
        SadadButton(
            text = stringResource(R.string.contactpicker_not_now),
            style = SadadButtonStyle.SURFACE,
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        )
      }
      ContactPermission.Granted -> {
        SadadField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            label = stringResource(R.string.contactpicker_search),
            singleLine = true,
            fieldModifier = Modifier.fillMaxWidth().testTag(ContactPickerTags.SEARCH),
        )
        when {
          state.loading ->
              // The single read the permission granted; it is over in one query loop.
              CircularProgressIndicator(Modifier.size(28.dp).align(Alignment.CenterHorizontally))
          state.rows.isEmpty() ->
              Text(
                  stringResource(R.string.contactpicker_empty),
                  style = MaterialTheme.typography.bodyLarge,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.align(Alignment.CenterHorizontally),
              )
          else -> {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
              items(state.rows) { row ->
                Column(
                    modifier =
                        Modifier.fillMaxWidth()
                            .clickable { viewModel.onPick(row) }
                            .padding(12.dp)
                            .testTag(ContactPickerTags.row(row.name)),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                  Text(row.name, style = MaterialTheme.typography.bodyLarge)
                  row.numbers.forEach { number -> NumberLine(number) }
                }
              }
            }
          }
        }
      }
    }
  }

  state.chooser?.let { chooser ->
    AlertDialog(
        onDismissRequest = viewModel::dismissChooser,
        title = { Text(stringResource(R.string.contactpicker_chooser_title)) },
        text = {
          Column { chooser.numbers.forEach { number -> ChooserLine(number, viewModel) } }
        },
        confirmButton = {},
        dismissButton = {
          TextButton(onClick = viewModel::dismissChooser) {
            Text(stringResource(R.string.contactpicker_cancel))
          }
        },
    )
  }

  // One pick, delivered once: a calling callback would outlive an Activity recreation.
  LaunchedEffect(state.picked) {
    val pick = state.picked
    if (pick != null) {
      onResult(pick)
      viewModel.pickDelivered()
    }
  }
}

/** One device number: the digits as the owner wrote them, greyed with the reason's word. */
@Composable
private fun NumberLine(number: ContactNumberRow) {
  val label = number.status.label()
  val color =
      if (label == null) MaterialTheme.colorScheme.onSurface
      else MaterialTheme.colorScheme.onSurfaceVariant
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(number.raw, modifier = Modifier.weight(1f), color = color)
    label?.let { Text(stringResource(it), color = color) }
  }
}

/** One number of the open chooser, as the owner wrote it; picking hands one number back. */
@Composable
private fun ChooserLine(number: ContactNumberRow, viewModel: ContactPickerViewModel) {
  val label = number.status.label()
  TextButton(
      onClick = { viewModel.onChoose(number) },
      modifier = Modifier.fillMaxWidth().testTag(ContactPickerTags.number(number.raw)),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(number.raw)
      label?.let { Text(stringResource(it), style = MaterialTheme.typography.labelSmall) }
    }
  }
}

/** «don't ask again»: the app's settings page is the only way back to the permission. */
@Composable
private fun OpenSettingsButton(context: Context) {
  val openSettings = stringResource(R.string.contactpicker_open_settings)
  SadadButton(
      text = openSettings,
      onClick = {
        val intent =
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            )
        runCatching { context.startActivity(intent) }
            .onFailure { error ->
              // It is only the settings page that can refuse to open; the app itself is fine.
              if (error !is ActivityNotFoundException) throw error
            }
      },
      modifier = Modifier.fillMaxWidth().testTag(ContactPickerTags.SETTINGS),
  )
}

/** The route-side entry: real sources off [SadadApplication]'s graph. */
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
                      ContactPickerViewModel(
                          device = app.graph.deviceContacts(),
                          people = app.graph.contactPeople().filterNotNull(),
                          grantedNow = {
                            ContextCompat.checkSelfPermission(
                                app,
                                Manifest.permission.READ_CONTACTS,
                            ) == PackageManager.PERMISSION_GRANTED
                          },
                      )
                    }
                  },
          ),
      onResult = onResult,
      onBack = onBack,
      modifier = modifier,
  )
}

/** Walks the context chain for the [ComponentActivity] the permission rationale needs. */
private tailrec fun Context.findActivity(): ComponentActivity? =
    when (this) {
      is ComponentActivity -> this
      is ContextWrapper -> baseContext.findActivity()
      else -> null
    }
