package com.sadad.app.feature.contactpicker

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android's own phone-number picker. The system shows the owner's contacts and returns the one
 * number they chose, with a one-time grant to read just that row: the app holds no contacts
 * permission and never sees the rest of the address book (Play treats contacts as sensitive data).
 */
class PickPhoneContact : ActivityResultContract<Unit, Uri?>() {
  // A MIME type, not Phone.CONTENT_URI as data: a content URI in an outgoing intent without a
  // grant is a StrictMode ContentUriWithoutPermission violation (it killed debug builds).
  override fun createIntent(context: Context, input: Unit): Intent =
      Intent(Intent.ACTION_PICK).setType(Phone.CONTENT_TYPE)

  override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
      intent?.data.takeIf { resultCode == Activity.RESULT_OK }
}

/** The name and number of the row the picker returned, or null when it cannot be read. */
internal suspend fun readPicked(resolver: ContentResolver, uri: Uri): DeviceContact? =
    withContext(Dispatchers.IO) {
      try {
        resolver
            .query(uri, arrayOf(Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER), null, null, null)
            ?.use { cursor ->
              if (!cursor.moveToFirst()) return@use null
              val number = cursor.getString(1)?.trim().orEmpty()
              if (number.isEmpty()) return@use null
              DeviceContact(cursor.getString(0).orEmpty(), listOf(number))
            }
      } catch (_: SecurityException) {
        // The one-time grant is gone (the picker's activity was recreated): nothing to read.
        null
      }
    }

/**
 * Opens the system picker; [onPicked] gets the chosen contact, or null when the owner backed out or
 * the row could not be read. Nothing read here is saved, logged or uploaded by this function.
 */
@Composable
fun rememberPhoneContactPicker(onPicked: (DeviceContact?) -> Unit): () -> Unit {
  val resolver = LocalContext.current.contentResolver
  val scope = rememberCoroutineScope()
  val deliver by rememberUpdatedState(onPicked)
  val launcher =
      rememberLauncherForActivityResult(PickPhoneContact()) { uri ->
        if (uri == null) deliver(null) else scope.launch { deliver(readPicked(resolver, uri)) }
      }
  return { launcher.launch(Unit) }
}
